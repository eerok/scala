/*  NSC -- new Scala compiler
 * Copyright 2005-2013 LAMP/EPFL
 * @author
 */

package scala.tools.nsc
package transform

import scala.collection.{ mutable, immutable }
import scala.collection.mutable.ListBuffer
import symtab.Flags._
import util.TreeSet

/** This phase converts classes with parameters into Java-like classes with
 *  fields, which are assigned to from constructors.
 */
abstract class Constructors extends Transform with ast.TreeDSL {
  import global._
  import definitions._

  /** the following two members override abstract members in Transform */
  val phaseName: String = "constructors"

  protected def newTransformer(unit: CompilationUnit): Transformer =
    new ConstructorTransformer(unit)

  private val guardedCtorStats: mutable.Map[Symbol, List[Tree]] = perRunCaches.newMap[Symbol, List[Tree]]()
  private val ctorParams: mutable.Map[Symbol, List[Symbol]] = perRunCaches.newMap[Symbol, List[Symbol]]()

  class ConstructorTransformer(unit: CompilationUnit) extends Transformer {

    def transformClassTemplate(impl: Template): Template = {
      val clazz = impl.symbol.owner  // the transformed class
      val stats = impl.body          // the transformed template body
      val localTyper = typer.atOwner(impl, clazz)

      val specializedFlag: Symbol = clazz.info.decl(nme.SPECIALIZED_INSTANCE)
      val shouldGuard = (specializedFlag != NoSymbol) && !clazz.hasFlag(SPECIALIZED)

      case class ConstrInfo(
        constr: DefDef,               // The primary constructor
        constrParams: List[Symbol],   // ... and its parameters
        constrBody: Block             // ... and its body
      )
      // decompose primary constructor into the three entities above.
      val constrInfo: ConstrInfo = {
        stats find (_.symbol.isPrimaryConstructor) match {
          case Some(ddef @ DefDef(_, _, _, List(vparams), _, rhs @ Block(_, _))) =>
            ConstrInfo(ddef, vparams map (_.symbol), rhs)
          case x =>
            // AnyVal constructor is OK
            assert(clazz eq AnyValClass, "no constructor in template: impl = " + impl)
            return impl
        }
      }
      import constrInfo._

      // The parameter accessor fields which are members of the class
      val paramAccessors = clazz.constrParamAccessors

      // The constructor parameter corresponding to an accessor
      def parameter(acc: Symbol): Symbol = parameterNamed(acc.unexpandedName.getterName)

      // The constructor parameter with given name. This means the parameter
      // has given name, or starts with given name, and continues with a `$` afterwards.
      def parameterNamed(name: Name): Symbol = {
        def matchesName(param: Symbol) = param.name == name || param.name.startsWith(name + nme.NAME_JOIN_STRING)

        (constrParams filter matchesName) match {
          case Nil    => abort(name + " not in " + constrParams)
          case p :: _ => p
        }
      }

      var usesSpecializedField: Boolean = false

      // A transformer for expressions that go into the constructor
      val intoConstructorTransformer = new Transformer {
        def isParamRef(sym: Symbol) =
          sym.isParamAccessor &&
          sym.owner == clazz &&
          !(clazz isSubClass DelayedInitClass) &&
          !(sym.isGetter && sym.accessed.isVariable) &&
          !sym.isSetter
        private def possiblySpecialized(s: Symbol) = specializeTypes.specializedTypeVars(s).nonEmpty
        override def transform(tree: Tree): Tree = tree match {
          case Apply(Select(This(_), _), List()) =>
            // references to parameter accessor methods of own class become references to parameters
            // outer accessors become references to $outer parameter
            if (isParamRef(tree.symbol) && !possiblySpecialized(tree.symbol))
              gen.mkAttributedIdent(parameter(tree.symbol.accessed)) setPos tree.pos
            else if (tree.symbol.outerSource == clazz && !clazz.isImplClass)
              gen.mkAttributedIdent(parameterNamed(nme.OUTER)) setPos tree.pos
            else
              super.transform(tree)
          case Select(This(_), _) if (isParamRef(tree.symbol) && !possiblySpecialized(tree.symbol)) =>
            // references to parameter accessor field of own class become references to parameters
            gen.mkAttributedIdent(parameter(tree.symbol)) setPos tree.pos
          case Select(_, _) =>
            if (specializeTypes.specializedTypeVars(tree.symbol).nonEmpty)
              usesSpecializedField = true
            super.transform(tree)
          case _ =>
            super.transform(tree)
        }
      }

      // Move tree into constructor, take care of changing owner from `oldowner` to constructor symbol
      def intoConstructor(oldowner: Symbol, tree: Tree) =
        intoConstructorTransformer transform tree.changeOwner(oldowner -> constr.symbol)

      // Should tree be moved in front of super constructor call?
      def canBeMoved(tree: Tree) = tree match {
        case ValDef(mods, _, _, _) => (mods hasFlag PRESUPER | PARAMACCESSOR)
        case _                     => false
      }

      // Create an assignment to class field `to` with rhs `from`
      def mkAssign(to: Symbol, from: Tree): Tree =
        localTyper.typedPos(to.pos) { Assign(Select(This(clazz), to), from) }

      // Create code to copy parameter to parameter accessor field.
      // If parameter is $outer, check that it is not null so that we NPE
      // here instead of at some unknown future $outer access.
      def copyParam(to: Symbol, from: Symbol): Tree = {
        import CODE._
        val result = mkAssign(to, Ident(from))

        if (from.name != nme.OUTER ||
            from.tpe.typeSymbol.isPrimitiveValueClass) result
        else localTyper.typedPos(to.pos) {
          // `throw null` has the same effect as `throw new NullPointerException`, see JVM spec on instruction `athrow`
          IF (from OBJ_EQ NULL) THEN Throw(gen.mkZero(ThrowableClass.tpe)) ELSE result
        }
      }

      // The list of definitions that go into class
      val defBuf = new ListBuffer[Tree]

      // The auxiliary constructors, separate from the defBuf since they should
      // follow the primary constructor
      val auxConstructorBuf = new ListBuffer[Tree]

      // The list of statements that go into constructor after and including the superclass constructor call
      val constrStatBuf = new ListBuffer[Tree]

      // The list of early initializer statements that go into constructor before the superclass constructor call
      val constrPrefixBuf = new ListBuffer[Tree]

      // The early initialized field definitions of the class (these are the class members)
      val presupers = treeInfo.preSuperFields(stats)

      // generate code to copy pre-initialized fields
      for (stat <- constrBody.stats) {
        constrStatBuf += stat
        stat match {
          case ValDef(mods, name, _, _) if (mods hasFlag PRESUPER) =>
            // stat is the constructor-local definition of the field value
            val fields = presupers filter (_.getterName == name)
            assert(fields.length == 1)
            val to = fields.head.symbol
            if (!to.tpe.isInstanceOf[ConstantType])
              constrStatBuf += mkAssign(to, Ident(stat.symbol))
          case _ =>
        }
      }

      // Triage all template definitions to go into defBuf/auxConstructorBuf, constrStatBuf, or constrPrefixBuf.
      for (stat <- stats) stat match {
        case DefDef(_,_,_,_,_,rhs) =>
          // methods with constant result type get literals as their body
          // all methods except the primary constructor go into template
          stat.symbol.tpe match {
            case MethodType(List(), tp @ ConstantType(c)) =>
              defBuf += deriveDefDef(stat)(Literal(c) setPos _.pos setType tp)
            case _ =>
              if (stat.symbol.isPrimaryConstructor) ()
              else if (stat.symbol.isConstructor) auxConstructorBuf += stat
              else defBuf += stat
          }
        case ValDef(_, _, _, rhs) =>
          // val defs with constant right-hand sides are eliminated.
          // for all other val defs, an empty valdef goes into the template and
          // the initializer goes as an assignment into the constructor
          // if the val def is an early initialized or a parameter accessor, it goes
          // before the superclass constructor call, otherwise it goes after.
          // Lazy vals don't get the assignment in the constructor.
          if (!stat.symbol.tpe.isInstanceOf[ConstantType]) {
            if (rhs != EmptyTree && !stat.symbol.isLazy) {
              val rhs1 = intoConstructor(stat.symbol, rhs)
              (if (canBeMoved(stat)) constrPrefixBuf else constrStatBuf) += mkAssign(
                stat.symbol, rhs1)
            }
            defBuf += deriveValDef(stat)(_ => EmptyTree)
          }
        case ClassDef(_, _, _, _) =>
          // classes are treated recursively, and left in the template
          defBuf += new ConstructorTransformer(unit).transform(stat)
        case _ =>
          // all other statements go into the constructor
          constrStatBuf += intoConstructor(impl.symbol, stat)
      }

      // ----------- avoid making parameter-accessor fields for symbols accessed only within the primary constructor --------------

      // A sorted set of symbols that are known to be accessed outside the primary constructor.
      val accessedSyms = new TreeSet[Symbol]((x, y) => x isLess y)

      // a list of outer accessor symbols and their bodies
      var outerAccessors: List[(Symbol, Tree)] = List()

      val isDelayedInitSubclass = (clazz isSubClass DelayedInitClass)

      // Could symbol's definition be omitted, provided it is not accessed?
      // This is the case if the symbol is defined in the current class, and
      // ( the symbol is an object private parameter accessor field, or
      //   the symbol is an outer accessor of a final class which does not override another outer accessor. )
      def maybeOmittable(sym: Symbol) = sym.owner == clazz && (
        sym.isParamAccessor && sym.isPrivateLocal ||
        sym.isOuterAccessor && sym.owner.isEffectivelyFinal && !sym.isOverridingSymbol &&
        !isDelayedInitSubclass
      )

      // Is symbol known to be accessed outside of the primary constructor,
      // or is it a symbol whose definition cannot be omitted anyway?
      def mustbeKept(sym: Symbol) = isDelayedInitSubclass || !maybeOmittable(sym) || (accessedSyms contains sym)

      // A traverser to set accessedSyms and outerAccessors
      val accessTraverser = new Traverser {
        override def traverse(tree: Tree) = {
          tree match {
            case DefDef(_, _, _, _, _, body)
            if (tree.symbol.isOuterAccessor && tree.symbol.owner == clazz && clazz.isEffectivelyFinal) =>
              debuglog("outerAccessors += " + tree.symbol.fullName)
              outerAccessors ::= ((tree.symbol, body))
            case Select(_, _) =>
              if (!mustbeKept(tree.symbol)) {
                debuglog("accessedSyms += " + tree.symbol.fullName)
                accessedSyms addEntry tree.symbol
              }
              super.traverse(tree)
            case _ =>
              super.traverse(tree)
          }
        }
      }

      // first traverse all definitions except outeraccesors
      // (outeraccessors are avoided in accessTraverser)
      for (stat <- defBuf.iterator ++ auxConstructorBuf.iterator)
        accessTraverser.traverse(stat)

      // then traverse all bodies of outeraccessors which are accessed themselves
      // note: this relies on the fact that an outer accessor never calls another
      // outer accessor in the same class.
      for ((accSym, accBody) <- outerAccessors)
        if (mustbeKept(accSym)) accessTraverser.traverse(accBody)

      // Initialize all parameters fields that must be kept.
      val paramInits = paramAccessors filter mustbeKept map { acc =>
        // Check for conflicting symbol amongst parents: see bug #1960.
        // It would be better to mangle the constructor parameter name since
        // it can only be used internally, but I think we need more robust name
        // mangling before we introduce more of it.
        val conflict = clazz.info.nonPrivateMember(acc.name) filter (s => s.isGetter && !s.isOuterField && s.enclClass.isTrait)
        if (conflict ne NoSymbol)
          unit.error(acc.pos, "parameter '%s' requires field but conflicts with %s".format(acc.name, conflict.fullLocationString))

        copyParam(acc, parameter(acc))
      }

      /* Return a single list of statements, merging the generic class constructor with the
       * specialized stats. The original statements are retyped in the current class, and
       * assignments to generic fields that have a corresponding specialized assignment in
       * `specializedStats` are replaced by the specialized assignment.
       */
      def mergeConstructors(genericClazz: Symbol, originalStats: List[Tree], specializedStats: List[Tree]): List[Tree] = {
        val specBuf = new ListBuffer[Tree]
        specBuf ++= specializedStats

        def specializedAssignFor(sym: Symbol): Option[Tree] =
          specializedStats find {
            case Assign(sel @ Select(This(_), _), _) =>
              sel.symbol.isSpecialized && (nme.unspecializedName(sel.symbol.getterName) == sym.getterName)
            case _ => false
          }

        /* Rewrite calls to ScalaRunTime.array_update to the proper apply method in scala.Array.
         * Erasure transforms Array.update to ScalaRunTime.update when the element type is a type
         * variable, but after specialization this is a concrete primitive type, so it would
         * be an error to pass it to array_update(.., .., Object).
         */
        def rewriteArrayUpdate(tree: Tree): Tree = {
          val adapter = new Transformer {
            override def transform(t: Tree): Tree = t match {
              case Apply(fun @ Select(receiver, method), List(xs, idx, v)) if fun.symbol == arrayUpdateMethod =>
                localTyper.typed(Apply(gen.mkAttributedSelect(xs, arrayUpdateMethod), List(idx, v)))
              case _ => super.transform(t)
            }
          }
          adapter.transform(tree)
        }

        log("merging: " + originalStats.mkString("\n") + "\nwith\n" + specializedStats.mkString("\n"))
        val res = for (s <- originalStats; stat = s.duplicate) yield {
          log("merge: looking at " + stat)
          val stat1 = stat match {
            case Assign(sel @ Select(This(_), field), _) =>
              specializedAssignFor(sel.symbol).getOrElse(stat)
            case _ => stat
          }
          if (stat1 ne stat) {
            log("replaced " + stat + " with " + stat1)
            specBuf -= stat1
          }

          if (stat1 eq stat) {
            assert(ctorParams(genericClazz).length == constrParams.length)
            // this is just to make private fields public
            (new specializeTypes.ImplementationAdapter(ctorParams(genericClazz), constrParams, null, true))(stat1)

            val stat2 = rewriteArrayUpdate(stat1)
            // statements coming from the original class need retyping in the current context
            debuglog("retyping " + stat2)

            val d = new specializeTypes.Duplicator(Map[Symbol, Type]())
            d.retyped(localTyper.context1.asInstanceOf[d.Context],
                      stat2,
                      genericClazz,
                      clazz,
                      Map.empty)
          } else
            stat1
        }
        if (specBuf.nonEmpty)
          println("residual specialized constructor statements: " + specBuf)
        res
      }

      /* Add an 'if' around the statements coming after the super constructor. This
       * guard is necessary if the code uses specialized fields. A specialized field is
       * initialized in the subclass constructor, but the accessors are (already) overridden
       * and pointing to the (empty) fields. To fix this, a class with specialized fields
       * will not run its constructor statements if the instance is specialized. The specialized
       * subclass includes a copy of those constructor statements, and runs them. To flag that a class
       * has specialized fields, and their initialization should be deferred to the subclass, method
       * 'specInstance$' is added in phase specialize.
       */
      def guardSpecializedInitializer(stats: List[Tree]): List[Tree] = if (settings.nospecialization.value) stats else {
        // // split the statements in presuper and postsuper
        // var (prefix, postfix) = stats0.span(tree => !((tree.symbol ne null) && tree.symbol.isConstructor))
        // if (postfix.nonEmpty) {
        //   prefix = prefix :+ postfix.head
        //   postfix = postfix.tail
        // }

        if (usesSpecializedField && shouldGuard && stats.nonEmpty) {
          // save them for duplication in the specialized subclass
          guardedCtorStats(clazz) = stats
          ctorParams(clazz) = constrParams

          val tree =
            If(
              Apply(
                CODE.NOT (
                 Apply(gen.mkAttributedRef(specializedFlag), List())),
                List()),
              Block(stats, Literal(Constant(()))),
              EmptyTree)

          List(localTyper.typed(tree))
        }
        else if (clazz.hasFlag(SPECIALIZED)) {
          // add initialization from its generic class constructor
          val genericName  = nme.unspecializedName(clazz.name)
          val genericClazz = clazz.owner.info.decl(genericName.toTypeName)
          assert(genericClazz != NoSymbol, clazz)

          guardedCtorStats.get(genericClazz) match {
            case Some(stats1) => mergeConstructors(genericClazz, stats1, stats)
            case None => stats
          }
        } else stats
      }

      /*
       *  Translation scheme for DelayedInit
       *  ----------------------------------
       *
       *  Before returning, transformClassTemplate() rewrites DelayedInit subclasses.
       *  The list of statements that will end up in the primary constructor can be split into:
       *
       *    (a) up to and including the super-constructor call.
       *        These statements can occur only in the (bytecode-level) primary constructor.
       *
       *    (b) remaining statements
       *
       *  The purpose of DelayedInit is leaving (b) out of the primary constructor and have their execution "delayed".
       *
       *  The rewriting to achieve "delayed initialization" involves:
       *    (c) an additional, synthetic, public method encapsulating (b)
       *    (d) an additional, synthetic closure whose argless apply() just invokes (c)
       *    (e) after executing the statements in (a),
       *        the primary constructor instantiates (d) and passes it as argument
       *        to a `delayedInit()` invocation on the current instance.
       *        In turn, `delayedInit()` is a method defined as abstract in the `DelayedInit` trait
       *        so that it can be overridden (for an example see `scala.App`)
       *
       *  The following helper methods prepare Trees as part of this rewriting:
       *
       *    (f) `delayedEndpointDef()` prepares (c).
       *        A transformer, `constrStatTransformer`, is used to re-locate statements (b) from template-level
       *        to become statements in method (c). The main task here is re-formulating accesses to params
       *        of the primary constructors (to recap, (c) has zero-params) in terms of param-accessor fields.
       *        In a Delayed-Init subclass, each class-constructor gets a param-accessor field because `mustbeKept()` forces it.
       *
       *    (g) `delayedInitClosure()` prepares (d)
       *
       *    (h) `delayedInitCall()`    prepares the `delayedInit()` invocation referred to in (e)
       *
       *  Both (c) and (d) are added to the Template returned by `transformClassTemplate()`
       *
       *  A note of historic interest: Previously the rewriting for DelayedInit would include in the closure body
       *  all of the delayed initialization sequence, which in turn required:
       *    - reformulating "accesses-on-this" into "accesses-on-outer", and
       *    - adding public getters and setters.
       *
       *  @param stats the statements in (b) above
       *
       *  @return the DefDef for (c) above
       *
       * */
      def delayedEndpointDef(stats: List[Tree]): DefDef = {

        val methodName = currentUnit.freshTermName("delayedEndpoint$" + clazz.fullNameAsName('$').toString + "$")
        val methodSym  = clazz.newMethod(methodName, impl.pos, SYNTHETIC | FINAL)
        methodSym setInfoAndEnter MethodType(Nil, UnitClass.tpe)

        // changeOwner needed because the `stats` contained in the DefDef were owned by the template, not long ago.
        val blk       = Block(stats, gen.mkZero(UnitClass.tpe)).changeOwner(impl.symbol -> methodSym)
        val delayedDD = localTyper typed { DefDef(methodSym, Nil, blk) }

        delayedDD.asInstanceOf[DefDef]
      }

      /* @see overview at `delayedEndpointDef()` of the translation scheme for DelayedInit */
      def delayedInitClosure(delayedEndPointSym: MethodSymbol): ClassDef = {
        val satelliteClass = localTyper.typed {
          atPos(impl.pos) {
            val closureClass   = clazz.newClass(nme.delayedInitArg.toTypeName, impl.pos, SYNTHETIC | FINAL)
            val closureParents = List(AbstractFunctionClass(0).tpe)

            closureClass setInfoAndEnter new ClassInfoType(closureParents, newScope, closureClass)

            val outerField: TermSymbol = (
              closureClass
                newValue(nme.OUTER, impl.pos, PrivateLocal | PARAMACCESSOR)
                setInfoAndEnter clazz.tpe
            )
            val applyMethod: MethodSymbol = (
              closureClass
                newMethod(nme.apply, impl.pos, FINAL)
                setInfoAndEnter MethodType(Nil, ObjectClass.tpe)
            )
            val outerFieldDef     = ValDef(outerField)
            val closureClassTyper = localTyper.atOwner(closureClass)
            val applyMethodTyper  = closureClassTyper.atOwner(applyMethod)

            def applyMethodStat =
              applyMethodTyper.typed {
                atPos(impl.pos) {
                  val receiver = Select(This(closureClass), outerField)
                  Apply(Select(receiver, delayedEndPointSym), Nil)
                }
              }

            val applyMethodDef = DefDef(
              sym = applyMethod,
              vparamss = ListOfNil,
              rhs = Block(applyMethodStat, gen.mkAttributedRef(BoxedUnit_UNIT)))

            ClassDef(
              sym = closureClass,
              constrMods = Modifiers(0),
              vparamss = List(List(outerFieldDef)),
              body = applyMethodDef :: Nil,
              superPos = impl.pos)
          }
        }

        satelliteClass.asInstanceOf[ClassDef]
      }

      /* @see overview at `delayedEndpointDef()` of the translation scheme for DelayedInit */
      def delayedInitCall(closure: Tree) = localTyper.typedPos(impl.pos) {
        gen.mkMethodCall(This(clazz), delayedInitMethod, Nil, List(New(closure.symbol.tpe, This(clazz))))
      }

      /* Return a pair consisting of (all statements up to and including superclass and trait constr calls, rest) */
      def splitAtSuper(stats: List[Tree]) = {
        def isConstr(tree: Tree) = (tree.symbol ne null) && tree.symbol.isConstructor
        val (pre, rest0) = stats span (!isConstr(_))
        val (supercalls, rest) = rest0 span (isConstr(_))
        (pre ::: supercalls, rest)
      }

      val (uptoSuperStats, remainingConstrStats0) = splitAtSuper(constrStatBuf.toList)
      var remainingConstrStats = remainingConstrStats0

      /* XXX This is not corect: remainingConstrStats.nonEmpty excludes too much,
       * but excluding it includes too much.  The constructor sequence being mimicked
       * needs to be reproduced with total fidelity.
       *
       * See test case files/run/bug4680.scala, the output of which is wrong in many
       * particulars.
       */
      val needsDelayedInit = (isDelayedInitSubclass && remainingConstrStats.nonEmpty)

      if (needsDelayedInit) {
        val delayedHook: DefDef = delayedEndpointDef(remainingConstrStats)
        defBuf += delayedHook
        val hookCallerClass = {
          // transform to make the closure-class' default constructor assign the the outer instance to its param-accessor field.
          val drillDown = new ConstructorTransformer(unit)
          drillDown transform delayedInitClosure(delayedHook.symbol.asInstanceOf[MethodSymbol])
        }
        defBuf += hookCallerClass
        remainingConstrStats = delayedInitCall(hookCallerClass) :: Nil
      }

      // Assemble final constructor
      defBuf += deriveDefDef(constr)(_ =>
        treeCopy.Block(
          constrBody,
          paramInits ::: constrPrefixBuf.toList ::: uptoSuperStats :::
            guardSpecializedInitializer(remainingConstrStats),
          constrBody.expr))

      // Followed by any auxiliary constructors
      defBuf ++= auxConstructorBuf

      // Unlink all fields that can be dropped from class scope
      for (sym <- clazz.info.decls ; if !mustbeKept(sym))
        clazz.info.decls unlink sym

      // Eliminate all field definitions that can be dropped from template
      deriveTemplate(impl)(_ => defBuf.toList filter (stat => mustbeKept(stat.symbol)))
    } // transformClassTemplate

    private def checkUninitializedReads(cd: ClassDef) {
      val stats = cd.impl.body
      val clazz = cd.symbol

      // Inspect for obvious out-of-order initialization; concrete, eager vals or vars,
      // declared in this class, for which a reference to the member precedes its definition.
      def checkableForInit(sym: Symbol) = (
           (sym ne null)
        && (sym.isVal || sym.isVar)
        && !(sym hasFlag LAZY | DEFERRED | SYNTHETIC)
      )
      val uninitializedVals = mutable.Set[Symbol](
        stats collect { case vd: ValDef if checkableForInit(vd.symbol) => vd.symbol.accessedOrSelf }: _*
      )
      if (uninitializedVals.nonEmpty)
        log("Checking constructor for init order issues among: " + uninitializedVals.map(_.name).mkString(", "))

      for (stat <- stats) {
        // Checking the qualifier symbol is necessary to prevent a selection on
        // another instance of the same class from potentially appearing to be a forward
        // reference on the member in the current class.
        def check(tree: Tree) = {
          for (t <- tree) t match {
            case t: RefTree if uninitializedVals(t.symbol.accessedOrSelf) && t.qualifier.symbol == clazz =>
              unit.warning(t.pos, s"Reference to uninitialized ${t.symbol.accessedOrSelf}")
            case _ =>
          }
        }
        stat match {
          case vd: ValDef      =>
            // doing this first allows self-referential vals, which to be a conservative
            // warner we will do because it's possible though difficult for it to be useful.
            uninitializedVals -= vd.symbol.accessedOrSelf
            if (!vd.symbol.isLazy)
              check(vd.rhs)
          case _: MemberDef    => // skip other member defs
          case t               => check(t) // constructor body statement
        }
      }

    } // end of checkUninitializedReads()

    override def transform(tree: Tree): Tree = {
      tree match {
        case cd : ClassDef if !cd.symbol.isInterface && !isPrimitiveValueClass(cd.symbol) =>
          checkUninitializedReads(cd)
          deriveClassDef(cd)(transformClassTemplate)
        case _ =>
          super.transform(tree)
      }
    }

  } // ConstructorTransformer
}
