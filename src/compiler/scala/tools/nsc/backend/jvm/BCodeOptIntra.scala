/* NSC -- new Scala compiler
 * Copyright 2005-2012 LAMP/EPFL
 * @author  Martin Odersky
 */


package scala.tools.nsc
package backend
package jvm

import scala.tools.asm
import asm.Opcodes
import asm.optimiz.{ProdConsAnalyzer, Util}
import asm.tree.analysis.SourceValue
import asm.tree._

import scala.collection.{ mutable, immutable }
import collection.convert.Wrappers.JListWrapper

/*
 *  Optimize and tidy-up bytecode before it's serialized for good.
 *  This class focuses on
 *    - intra-method optimizations,
 *    - intra-class  optimizations, and
 *    - utilities for the above and for inter-procedural optimizations as well.
 *
 *  @author  Miguel Garcia, http://lamp.epfl.ch/~magarcia/ScalaCompilerCornerReloaded/
 *  @version 1.0
 *
 *  TODO Improving the Precision and Correctness of Exception Analysis in Soot, http://www.sable.mcgill.ca/publications/techreports/#report2003-3
 *
 */
abstract class BCodeOptIntra extends BCodeOptGCSavvyClosu {

  import global._

  final override def createBCodeCleanser(cnode: asm.tree.ClassNode, isIntraProgramOpt: Boolean) = {
    new BCodeCleanser(cnode, isIntraProgramOpt)
  }

  /*
   *  SI-6720: Avoid java.lang.VerifyError: Uninitialized object exists on backward branch.
   *
   *  Quoting from the JVM Spec, 4.9.2 Structural Constraints , http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html
   *
   *     There must never be an uninitialized class instance on the operand stack or in a local variable
   *     at the target of a backwards branch unless the special type of the uninitialized class instance
   *     at the branch instruction is merged with itself at the target of the branch (Sec. 4.10.2.4).
   *
   *  The Oracle JVM as of JDK 7 has started rejecting bytecode of the form:
   *
   *      NEW x
   *      DUP
   *      ... instructions loading ctor-args, involving a backedge
   *      INVOKESPECIAL <init>
   *
   *  `rephraseBackedgesInConstructorArgs()` overcomes the above by reformulating into:
   *
   *      ... instructions loading ctor-arg N
   *      STORE nth-arg
   *      ... instructions loading ctor-arg (N-1)
   *      STORE (n-1)th-arg
   *      ... and so on
   *      STORE 1st-arg
   *      NEW x
   *      DUP
   *      LOAD 1st-arg
   *      ...
   *      LOAD nth-arg
   *      INVOKESPECIAL <init>
   *
   *  A warning informs that, in the rewritten version, `NEW x` comes after the code to compute arguments.
   *  It's either that (potential) behavioral change or VerifyError.
   *  "Behavioral change" that is, in case the class being instantiated has a side-effecting static initializer.
   *
   *  @param nxtIdx0  next available index for local variable
   *  @param bes      backedges in ctor-arg section
   *  @param newInsn  left  bracket of the section where backedges were found
   *  @param initInsn right bracket of the section where backedges were found
   *
   */
  def rephraseBackedgesInCtorArg(nxtIdx0:  Int,
                                 bes:      _root_.java.util.Map[asm.tree.JumpInsnNode, asm.tree.LabelNode],
                                 cnode:    asm.tree.ClassNode,
                                 mnode:    asm.tree.MethodNode,
                                 newInsn:  asm.tree.TypeInsnNode,
                                 initInsn: MethodInsnNode): Int = {

    var nxtIdx = nxtIdx0

    import collection.convert.Wrappers.JSetWrapper
    for(
      entry <- JSetWrapper(bes.entrySet());
      jump  = entry.getKey;
      label = entry.getValue
    ) {
      warning(
        s"Backedge found in contructor-args section, in method ${methodSignature(cnode, mnode)} " +
        s"(jump ${insnPos(jump, mnode)} , target ${insnPos(label, mnode)} ). " +
        s"In order to avoid SI-6720, adding LOADs and STOREs for arguments. "  +
        s"As a result, ${newInsn.desc} is now instantiated after evaluating all ctor-arguments, " +
        s"on the assumption such class has not static-ctor performing visible side-effects that could make a difference."
      )
    }

    assert(newInsn.getOpcode == asm.Opcodes.NEW)
    val dupInsn = newInsn.getNext
    val paramTypes = BType.getMethodType(initInsn.desc).getArgumentTypes

    val stream = mnode.instructions
    stream.remove(newInsn)
    stream.remove(dupInsn)
    stream.insertBefore(initInsn, newInsn)
    stream.insertBefore(initInsn, dupInsn)

    for(i <- (paramTypes.length - 1) to 0 by -1) {
      val pt = paramTypes(i)
      val idxVar = nxtIdx
      nxtIdx += pt.getSize
      val load  = new asm.tree.VarInsnNode(pt.getOpcode(asm.Opcodes.ILOAD),  idxVar)
      val store = new asm.tree.VarInsnNode(pt.getOpcode(asm.Opcodes.ISTORE), idxVar)
      stream.insertBefore(newInsn, store)
      stream.insert(dupInsn,load)
    }

    nxtIdx
  } // end of method rephraseBackedgesInCtorArg()

  /*
   *  All methods in this class can-multi-thread
   */
  class EssentialCleanser(cnode: asm.tree.ClassNode) {

    val jumpsCollapser      = new asm.optimiz.JumpChainsCollapser()
    val labelsCleanup       = new asm.optimiz.LabelsCleanup()
    val danglingExcHandlers = new asm.optimiz.DanglingExcHandlers()

    /*
     *  This method performs a few intra-method optimizations:
     *    - collapse a multi-jump chain to target its final destination via a single jump
     *    - remove unreachable code
     *    - remove those LabelNodes and LineNumbers that aren't in use
     *
     *  Some of the above are applied repeatedly until no further reductions occur.
     *
     *  Node: what ICode calls reaching-defs is available as asm.tree.analysis.SourceInterpreter, but isn't used here.
     *
     */
    final def cleanseMethod(cName: String, mnode: asm.tree.MethodNode): Boolean = {

      var changed = false
      var keepGoing = false

      do {
        keepGoing = false

        jumpsCollapser.transform(mnode)            // collapse a multi-jump chain to target its final destination via a single jump
        keepGoing |= jumpsCollapser.changed

        keepGoing |= removeUnreachableCode(mnode)

        labelsCleanup.transform(mnode)             // remove those LabelNodes and LineNumbers that aren't in use
        keepGoing |= labelsCleanup.changed

        danglingExcHandlers.transform(mnode)
        keepGoing |= danglingExcHandlers.changed

        changed |= keepGoing

      } while (keepGoing)

      changed

    }

    /*
     * Detects and removes unreachable code.
     *
     * Should be used last in a transformation chain, before stack map frames are computed.
     * The Java 6 verifier demands frames be available even for dead code.
     * Those frames are tricky to compute, http://asm.ow2.org/doc/developer-guide.html#deadcode
     * The problem is avoided altogether by not emitting unreachable code in the first place.
     *
     * This method has a lower memory footprint than `asm.optimiz.UnreachableCode`
     * Otherwise both versions accomplish the same.
     *
     */
    final def removeUnreachableCode(mnode: MethodNode): Boolean = {

      val landing  = mutable.Set.empty[AbstractInsnNode]
      val suspect  = mutable.Set.empty[AbstractInsnNode]
      val worklist = new mutable.Stack[AbstractInsnNode]

          def transfer(to: AbstractInsnNode) {
            if (to == null)  { return }
            suspect -= to
            if (landing(to)) { return }
            landing += to
            if (to.getType == AbstractInsnNode.LABEL) { transfer(to.getNext) }
            else {
              worklist push to
            }
          }

          def transfers(labels: _root_.java.util.List[LabelNode]) {
            for(lbl <- JListWrapper(labels)) { transfer(lbl) }
          }

          def makeSuspect(s: AbstractInsnNode) {
            if (s == null) { return }
            if (!landing(s)) {
              suspect += s
             }
          }

      val stream = mnode.instructions
      transfer(stream.getFirst)
      for(tcb <- JListWrapper(mnode.tryCatchBlocks)) { transfer(tcb.handler) }

      while (worklist.nonEmpty) {
        var reach = worklist.pop()
        while (reach != null) {

          reach.getType match {
            case AbstractInsnNode.LABEL =>
              transfer(reach)
              reach = null
            case AbstractInsnNode.JUMP_INSN =>
              val ji = reach.asInstanceOf[JumpInsnNode]
              if (ji.getOpcode == Opcodes.JSR) {
                return false // don't touch methods containing subroutines (perhaps was inlined, scalac doesn't emit JSR/RET)
              }
              if (Util.isCondJump(reach)) {
                transfer(ji.label)
                transfer(reach.getNext)
              } else {
                assert(reach.getOpcode == Opcodes.GOTO)
                transfer(ji.label)
                makeSuspect(reach.getNext)
              }
              reach = null
            case AbstractInsnNode.LOOKUPSWITCH_INSN =>
              val lsi = reach.asInstanceOf[LookupSwitchInsnNode]
              transfer(lsi.dflt)
              transfers(lsi.labels)
              reach = null
            case AbstractInsnNode.TABLESWITCH_INSN =>
              val tsi = reach.asInstanceOf[TableSwitchInsnNode]
              transfer(tsi.dflt)
              transfers(tsi.labels)
              reach = null
            case AbstractInsnNode.INSN =>
              val isATHROW = (reach.getOpcode == Opcodes.ATHROW)
              if (isATHROW || Util.isRETURN(reach)) {
                makeSuspect(reach.getNext)
                reach = null
              }
            case _ =>
              if (reach.getOpcode == Opcodes.RET) {
                return false // don't touch methods containing subroutines (perhaps was inlined, scalac doesn't emit JSR/RET)
              }
              ()
          }

          if (reach != null) {
            reach = reach.getNext
          }

        }
      }

      // pruning
      var changed = false
      for(s <- suspect) {
        var current = s
        while (current != null && !landing(current) && stream.contains(current)) {
          val nxt = current.getNext
          if (current.getType != AbstractInsnNode.LABEL) { // let asm.optimiz.LabelsCleanup take care of LabelNodes
            changed = true
            stream remove current
          }
          current = nxt
        }
      }

      changed
    }

    /*
     *  Removes dead code.
     *
     *  When writing classfiles with "optimization level zero" (ie -neo:GenBCode)
     *  the very least we want to do is remove dead code beforehand,
     *  so as to prevent an artifact of stack-frames computation from showing up,
     *  the artifact described at http://asm.ow2.org/doc/developer-guide.html#deadcode
     *  That artifact results from the requirement by the Java 6 split verifier
     *  that a stack map frame be available for each basic block, even unreachable ones.
     *
     *  Just removing dead code might leave stale LocalVariableTable entries
     *  thus `cleanseMethod()` also gets rid of those.
     *
     */
    final def codeFixupDCE() {
      ifDebug { runTypeFlowAnalysis() }
      val iter = cnode.methods.iterator()
      while (iter.hasNext) {
        val mnode = iter.next()
        if (Util.hasBytecodeInstructions(mnode)) {
          Util.computeMaxLocalsMaxStack(mnode)
          cleanseMethod(cnode.name, mnode) // remove unreachable code
        }
      }
      ifDebug { runTypeFlowAnalysis() }
    }

    /*
     *  Elide redundant outer-fields for Late-Closure-Classes.
     *
     *  @param lccsToSquashOuterPointer in this case, what the name implies.
     *  @param dClosureEndpoint         a map with entries (LCC-internal-name -> endpoint-as-MethodNode)
     *
     */
    final def codeFixupSquashLCC(lccsToSquashOuterPointer: List[ClassNode],
                                 dClosureEndpoint: Map[String, MethodNode]) {
      if (!cnode.isStaticModule && lccsToSquashOuterPointer.nonEmpty) {
        val sq = new LCCOuterSquasher(cnode, lccsToSquashOuterPointer, dClosureEndpoint)
        sq.squashOuterForLCC()
      }
    }

    //--------------------------------------------------------------------
    // Type-flow analysis
    //--------------------------------------------------------------------

    final def runTypeFlowAnalysis() {
      for(m <- JListWrapper(cnode.methods); if asm.optimiz.Util.hasBytecodeInstructions(m)) {
        runTypeFlowAnalysis(m)
      }
    }

    final def runTypeFlowAnalysis(mnode: MethodNode) {

      import asm.tree.analysis.{ Analyzer, Frame }
      import asm.tree.AbstractInsnNode

      Util.computeMaxLocalsMaxStack(mnode)
      val tfa = new Analyzer[TFValue](new TypeFlowInterpreter)
      tfa.analyze(cnode.name, mnode)
      val frames: Array[Frame[TFValue]]   = tfa.getFrames()
      val insns:  Array[AbstractInsnNode] = mnode.instructions.toArray()
      var i = 0
      while (i < insns.length) {
        if (frames(i) == null && insns(i) != null) {
          // TODO abort("There should be no unreachable code left by now.")
        }
        i += 1
      }
    }

  } // end of class EssentialCleanser

  class QuickCleanser(cnode: asm.tree.ClassNode) extends EssentialCleanser(cnode) {

    val copyPropagator      = new asm.optimiz.CopyPropagator
    val deadStoreElim       = new asm.optimiz.DeadStoreElim
    val ppCollapser         = new asm.optimiz.PushPopCollapser
    val jumpReducer         = new asm.optimiz.JumpReducer
    val nullnessPropagator  = new asm.optimiz.NullnessPropagator
    val constantFolder      = new asm.optimiz.ConstantFolder

    //--------------------------------------------------------------------
    // First optimization pack
    //--------------------------------------------------------------------

    /*
     *  Intra-method optimizations performed until a fixpoint is reached.
     */
    final def basicIntraMethodOpt(mnode: asm.tree.MethodNode) {
      val cName = cnode.name
      var keepGoing = false
      do {
        keepGoing = false

        keepGoing |= cleanseMethod(cName, mnode)
        keepGoing |= elimRedundantCode(cName, mnode)

        nullnessPropagator.transform(cName, mnode);   // infers null resp. non-null reaching certain program points, simplifying control-flow based on that.
        keepGoing |= nullnessPropagator.changed

        constantFolder.transform(cName, mnode);       // propagates primitive constants, performs ops and simplifies control-flow based on that.
        keepGoing |= constantFolder.changed

      } while (keepGoing)
    }

    //--------------------------------------------------------------------
    // Second optimization pack
    //--------------------------------------------------------------------

    /*
     *  This method performs a few intra-method optimizations,
     *  aimed at reverting the extra copying introduced by inlining:
     *    - replace the last link in a chain of data accesses by a direct access to the chain-start.
     *    - dead-store elimination
     *    - remove those (producer, consumer) pairs where the consumer is a DROP and
     *      the producer has its value consumed only by the DROP in question.
     *
     */
    final def elimRedundantCode(cName: String, mnode: asm.tree.MethodNode): Boolean = {
      var changed   = false
      var keepGoing = false

      do {

        keepGoing = false

        copyPropagator.transform(cName, mnode) // replace the last link in a chain of data accesses by a direct access to the chain-start.
        keepGoing |= copyPropagator.changed

        deadStoreElim.transform(cName, mnode)  // replace STOREs to non-live local-vars with DROP instructions.
        keepGoing |= deadStoreElim.changed

        ppCollapser.transform(cName, mnode)    // propagate a DROP to the instruction(s) that produce the value in question, drop the DROP.
        keepGoing |= ppCollapser.changed

        jumpReducer.transform(mnode)           // simplifies branches that need not be taken to get to their destination.
        keepGoing |= jumpReducer.changed

        changed = (changed || keepGoing)

      } while (keepGoing)

      changed
    }

  } // end of class QuickCleanser

  /*
   *  Intra-method optimizations. Upon visiting each method in an asm.tree.ClassNode,
   *  optimizations are applied iteratively until a fixpoint is reached.
   *
   *  All optimizations implemented here can do based solely on information local to the method
   *  (in particular, no lookups on `exemplars` are performed).
   *  That way, intra-method optimizations can be performed in parallel (in pipeline-2)
   *  while GenBCode's pipeline-1 keeps building more `asm.tree.ClassNode`s.
   *  Moreover, pipeline-2 is realized by a thread-pool.
   *
   *  The entry point is `cleanseClass()`.
   */
  final class BCodeCleanser(cnode: asm.tree.ClassNode, isIntraProgramOpt: Boolean) extends QuickCleanser(cnode) with BCodeCleanserIface {

    val unboxElider           = new asm.optimiz.UnBoxElider
    val lvCompacter           = new asm.optimiz.LocalVarCompact
    val unusedPrivateDetector = new asm.optimiz.UnusedPrivateDetector()

    /*
     *  Laundry-list of all optimizations that might possibly be applied
     *  ----------------------------------------------------------------
     *
     *  The intra-method optimizations below are performed until a fixpoint is reached.
     *  They are grouped somewhat arbitrarily into:
     *    - those performed by `cleanseMethod()`
     *    - those performed by `elimRedundandtCode()`
     *    - nullness propagation
     *    - constant folding
     *
     *  After the fixpoint has been reached, three more intra-method optimizations are performed just once
     *  (further applications wouldn't reduce any further):
     *    - eliding box/unbox pairs
     *    - eliding redundant local vars
     *
     *  Afterwards, some intra-class optimizations are performed repeatedly:
     *    - those private members of a class which see no use are elided
     *    - tree-shake unused closures, minimize the fields of those remaining
     *
     *  While other intra-class optimizations are performed just once:
     *    - minimization of closure-allocations
     *    - add caching for closure recycling
     *    - refresh the InnerClasses JVM attribute
     *
     *
     *  Fine print: Which optimizations are actually applied to which classes
     *  ---------------------------------------------------------------------
     *
     *  The above describes the common case, glossing over dclosure-specific optimizations.
     *  In fact, not all optimizations are applicable to any given ASM ClassNode, as described below.
     *
     *  The ClassNodes that reach `cleanseClass()` can be partitioned into:
     *    (1) master classes;
     *    (2) dclosures;
     *    (3) elided classes; and
     *    (4) none of the previous ones. Examples of (4) are:
     *        (4.a) a traditional closure lacking any dclosures, or
     *        (4.b) a plain class without dclosures.
     *
     *  The categories above make clear why:
     *    (a) an elided class need not be optimized (nobody will notice the difference)
     *        that's why `cleanseClass()` just returns on seeing one.
     *    (b) only master classes (and their dclosures) go through the following optimizations:
     *          - shakeAndMinimizeClosures()
     *          - minimizeDClosureAllocations()
     *        To recap, `cleanseClass()` executes in a Worker2 thread. The dclosure-specific optimizations are organized
     *        such that exclusive write access to a dclosure is granted to its master class (there's always one).
     *
     *  In summary, (1) and (4) should have the (chosen level of) optimizations applied,
     *  with (1) also amenable to dclosure-specific optimizations.
     *
     *  An introduction to ASM bytecode rewriting can be found in Ch. 8. "Method Analysis" in
     *  the ASM User Guide, http://download.forge.objectweb.org/asm/asm4-guide.pdf
     *
     *  TODO refreshInnerClasses() should also be run on dclosures
     */
    def cleanseClass() {

      // a dclosure is optimized together with its master class by `DClosureOptimizer`
      assert(!isDClosure(cnode.name), "A delegating-closure pretented to be optimized as plain class: " + cnode.name)

      val bt = lookupRefBType(cnode.name)
      if (elidedClasses.contains(bt)) { return }

      // (1) intra-method
      intraMethodFixpoints(full = true)

      if (isIntraProgramOpt && isMasterClass(bt)) {

        val dcloptim   = new DClosureOptimizerImpl(cnode)
        var keepGoing  = false
        var rounds     = 0
        val MAX_ROUNDS = 10

        do {

            // (2) intra-class, useful for master classes, but can by applied to any class.
            keepGoing  = removeUnusedLiftedMethods()

            // (3) inter-class but in a controlled way (any given class is mutated by at most one Worker2 instance).
            keepGoing |= dcloptim.minimizeDClosureFields()

            if (keepGoing) { intraMethodFixpoints(full = false) }

            rounds += 1

        } while (
          keepGoing && (rounds < MAX_ROUNDS)
        )

        dcloptim.minimizeDClosureAllocations()

        if (dcloptim.treeShakeUnusedDClosures()) {
          rounds = 0
          do { rounds += 1 }
          while (
            removeUnusedLiftedMethods() &&
            (rounds < MAX_ROUNDS)
          )
        }

      }

      for(mnode <- cnode.toMethodList; if Util.hasBytecodeInstructions(mnode)) {
        rephraseBackedgesSlow(mnode)
      }

    } // end of method cleanseClass()

    /*
     *  This version can cope with <init> super-calls (which are valid only in ctors) as well as
     *  with all code reductions the optimizer performs. This flexibility requires a producers-consumers analysis,
     *  which makes the rewriting slower than its counterpart in GenBCode.
     *
     *  @see long description at `rephraseBackedgesInCtorArg()`
     *
     *  @return true iff the method body was mutated
     */
    def rephraseBackedgesSlow(mnode: MethodNode): Boolean = {

      val inits =
        for(
          i <- mnode.instructions.toList;
          if i.getType == AbstractInsnNode.METHOD_INSN;
          mi = i.asInstanceOf[MethodInsnNode];
          if (mi.name == "<init>") && (mi.desc != "()V")
        ) yield mi;
      if (inits.isEmpty) { return false }

      val cp = ProdConsAnalyzer.create()
      cp.analyze(cnode.name , mnode)

      for(init <- inits) {
        val receiverSV: SourceValue = cp.frameAt(init).getReceiver(init)
        assert(
          receiverSV.insns.size == 1,
          s"A unique NEW instruction cannot be determined for ${insnPosInMethodSignature(init, mnode, cnode)}"
        )
        val dupInsn = receiverSV.insns.iterator().next()
        val bes: java.util.Map[JumpInsnNode, LabelNode] = Util.backedges(dupInsn, init)
        if (!bes.isEmpty) {
          val newInsn = dupInsn.getPrevious.asInstanceOf[TypeInsnNode]
          mnode.maxLocals = rephraseBackedgesInCtorArg(mnode.maxLocals, bes, cnode, mnode, newInsn, init)
        }
      }

      true
    } // end of method rephraseBackedgesSlow()

    /*
     *  intra-method optimizations
     */
    def intraMethodFixpoints(full: Boolean) {

      for(mnode <- cnode.toMethodList; if Util.hasBytecodeInstructions(mnode)) {

        Util.computeMaxLocalsMaxStack(mnode)

        basicIntraMethodOpt(mnode)                 // intra-method optimizations performed until a fixpoint is reached

        if (full) {
          unboxElider.transform(cnode.name, mnode) // remove box/unbox pairs (this transformer is more expensive than most)
          lvCompacter.transform(mnode)             // compact local vars, remove dangling LocalVariableNodes.
        }

        ifDebug { runTypeFlowAnalysis(mnode) }

      }

    }

    /**
     *  Elides unused private lifted methods (ie neither fields nor constructors) be they static or instance.
     *  How do such methods become "unused"? For example, dead-code-elimination may have removed all invocations to them.
     *
     *  Other unused private members could also be elided, but that might come as unexpected,
     *  ie a situation where (non-lifted) private methods vanish on the way from source code to bytecode.
     *
     *  Those bytecode-level private methods that originally were local (in the Scala sense)
     *  are recognized because isLiftedMethod == true.
     *  In particular, all methods originally local to a delegating-closure's apply() are private isLiftedMethod.
     *  (Sidenote: the endpoint of a dclosure is public, yet has isLiftedMethod == true).
     *
     * */
    private def removeUnusedLiftedMethods(): Boolean = {
      import collection.convert.Wrappers.JSetWrapper
      var changed = false
      unusedPrivateDetector.transform(cnode)
      for(im <- JSetWrapper(unusedPrivateDetector.elidableInstanceMethods)) {
        if (im.isLiftedMethod && !Util.isConstructor(im)) {
          changed = true
          cnode.methods.remove(im)
        }
      }

      changed
    }

  } // end of class BCodeCleanser

  /*
   * One of the intra-method optimizations (dead-code elimination)
   * and a few of the inter-procedural ones (inlining)
   * may have caused the InnerClasses JVM attribute to become stale
   * (e.g. some inner classes that were mentioned aren't anymore,
   * or inlining added instructions referring to inner classes not yet accounted for)
   *
   * This method takes care of SI-6546 "Optimizer leaves references to classes that have been eliminated by inlining"
   *
   * TODO SI-6759 Seek clarification about necessary and sufficient conditions to be listed in InnerClasses JVM attribute (GenASM).
   * The JVM spec states in Sec. 4.7.6 that
   *   for each CONSTANT_Class_info (constant-pool entry) which represents a class or interface that is not a member of a package
   * an entry should be made in the class' InnerClasses JVM attribute.
   * According to the above, the mere fact an inner class is mentioned in, for example, an annotation
   * wouldn't be reason enough for adding it to the InnerClasses JVM attribute.
   * However that's what GenASM does. Instead, this method scans only those internal names that will make it to a CONSTANT_Class_info.
   *
   * `refreshInnerClasses()` requires that `exemplars` already tracks
   * each BType of hasObjectSort variety that is mentioned in the ClassNode.
   *
   * can-multi-thread
   */
  final def refreshInnerClasses(cnode: ClassNode) {

    import scala.collection.convert.Wrappers.JListWrapper

    val refedInnerClasses = mutable.Set.empty[BType]
    cnode.innerClasses.clear()

        def visitInternalName(value: String) {
          if (value == null) {
            return
          }
          var bt = lookupRefBType(value)
          if (bt.isArray) {
            bt = bt.getElementType
          }
          if (bt.hasObjectSort && !bt.isPhantomType && (bt != BoxesRunTime) && !elidedClasses.contains(bt)) {
            if (exemplars.get(bt).isInnerClass) {
              refedInnerClasses += bt
            }
          }
        }

        def visitDescr(desc: String) {
          val bt = descrToBType(desc)
          if (bt.isArray) { visitDescr(bt.getElementType.getDescriptor) }
          else if (bt.sort == BType.METHOD) {
            visitDescr(bt.getReturnType.getDescriptor)
            bt.getArgumentTypes foreach { at => visitDescr(at.getDescriptor) }
          } else if (bt.hasObjectSort) {
            visitInternalName(bt.getInternalName)
          }
        }

    visitInternalName(cnode.name)
    visitInternalName(cnode.superName)
    JListWrapper(cnode.interfaces) foreach visitInternalName
    visitInternalName(cnode.outerClass)
    JListWrapper(cnode.fields)  foreach { fn: FieldNode  => visitDescr(fn.desc) }
    JListWrapper(cnode.methods) foreach { mn: MethodNode => visitDescr(mn.desc) }

    // annotations not visited because they store class names in CONSTANT_Utf8_info as opposed to the CONSTANT_Class_info that matter for InnerClasses.

    // TODO JDK8 the BootstrapMethodsAttribute may point via bootstrap_arguments to one or more CONSTANT_Class_info entries

    for(m <- JListWrapper(cnode.methods)) {

      JListWrapper(m.exceptions) foreach visitInternalName

      JListWrapper(m.tryCatchBlocks) foreach { tcb => visitInternalName(tcb.`type`) }

      val iter = m.instructions.iterator()
      while (iter.hasNext) {
        val insn = iter.next()
        insn match {
          case ti: TypeInsnNode   => visitInternalName(ti.desc) // an intenal name, actually
          case fi: FieldInsnNode  => visitInternalName(fi.owner); visitDescr(fi.desc)
          case mi: MethodInsnNode => visitInternalName(mi.owner); visitDescr(mi.desc)
          case ivd: InvokeDynamicInsnNode => () // TODO
          case ci: LdcInsnNode    =>
            ci.cst match {
              case t: asm.Type => visitDescr(t.getDescriptor)
              case _           => ()
            }
          case ma: MultiANewArrayInsnNode => visitDescr(ma.desc)
          case _ => ()
        }
      }

    }

    // cnode is a class being compiled, thus its Tracked.directMemberClasses should be defined
    // TODO check whether any member class has been elided? (but only anon-closure-classes can be elided)
    refedInnerClasses ++= exemplars.get(lookupRefBType(cnode.name)).directMemberClasses

    addInnerClassesASM(cnode, refedInnerClasses)

  } // end of method refreshInnerClasses()

} // end of class BCodeOptIntra
