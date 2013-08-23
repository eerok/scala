/* NSC -- new Scala compiler
 * Copyright 2005-2012 LAMP/EPFL
 * @author  Martin Odersky
 */


package scala
package tools.nsc
package backend
package jvm

import scala.collection.{ mutable, immutable }
import scala.annotation.switch

import scala.tools.asm

/*
 *  Prepare in-memory representations of classfiles using the ASM Tree API, and serialize them to disk.
 *
 *  `BCodePhase.apply(CompilationUnit)` is invoked by some external force and that sets in motion:
 *    - visiting each ClassDef contained in that CompilationUnit
 *    - lowering the ClassDef into:
 *          (a) an optional mirror class,
 *          (b) a plain class, and
 *          (c) an optional bean class.
 *    - each of the items (a), (b), (c) is placed on queue `q2`.
 *      Those items will be transferred from that queue to `q3` by Worker2,
 *      whose job is lowering a ClassNode into a byte-array (ie into a classfile)
 *    - the classfilse are serialized in `drainQ3()`
 *
 *  Plain, mirror, and bean classes are built respectively by PlainClassBuilder, JMirrorBuilder, and JBeanInfoBuilder.
 *
 *  @author  Miguel Garcia, http://lamp.epfl.ch/~magarcia/ScalaCompilerCornerReloaded/
 *  @version 1.0
 *
 */
abstract class GenBCode extends BCodeSyncAndTry {
  import global._

  val phaseName = "jvm"

  override def newPhase(prev: Phase) = new BCodePhase(prev)

  final class PlainClassBuilder(cunit: CompilationUnit) extends SyncAndTryBuilder(cunit)

  class BCodePhase(prev: Phase) extends StdPhase(prev) {

    override def name = phaseName
    override def description = "Generate bytecode from ASTs using the ASM library"
    override def erasedTypes = true

    private var bytecodeWriter  : BytecodeWriter   = null
    private var mirrorCodeGen   : JMirrorBuilder   = null
    private var beanInfoCodeGen : JBeanInfoBuilder = null

    private var needsOutFolder  = false // whether getOutFolder(claszSymbol) should be invoked for each claszSymbol

    /* ---------------- q2 ---------------- */

    case class Item2(arrivalPos:   Int,
                     mirror:       asm.tree.ClassNode,
                     plain:        asm.tree.ClassNode,
                     bean:         asm.tree.ClassNode,
                     outFolder:    scala.tools.nsc.io.AbstractFile) {
      def isPoison = { arrivalPos == Int.MaxValue }
    }

    private val poison2 = Item2(Int.MaxValue, null, null, null, null)
    private val q2 = new _root_.java.util.LinkedList[Item2]

    /* ---------------- q3 ---------------- */

    /*
     *  An item of queue-3 (the last queue before serializing to disk) contains three of these
     *  (one for each of mirror, plain, and bean classes).
     *
     *  @param jclassName  internal name of the class
     *  @param jclassBytes bytecode emitted for the class SubItem3 represents
     */
    case class SubItem3(
      jclassName:  String,
      jclassBytes: Array[Byte]
    )

    case class Item3(arrivalPos: Int,
                     mirror:     SubItem3,
                     plain:      SubItem3,
                     bean:       SubItem3,
                     outFolder:  scala.tools.nsc.io.AbstractFile) {

      def isPoison  = { arrivalPos == Int.MaxValue }
    }
    private val i3comparator = new java.util.Comparator[Item3] {
      override def compare(a: Item3, b: Item3) = {
        if (a.arrivalPos < b.arrivalPos) -1
        else if (a.arrivalPos == b.arrivalPos) 0
        else 1
      }
    }
    private val poison3 = Item3(Int.MaxValue, null, null, null, null)
    private val q3 = new java.util.PriorityQueue[Item3](1000, i3comparator)

    val caseInsensitively = mutable.Map.empty[String, Symbol]

    /*
     *  Checks for duplicate internal names case-insensitively,
     *  builds ASM ClassNodes for mirror, plain, and bean classes;
     *  enqueues them in queue-2.
     *
     */
    def visit(arrivalPos: Int, cd: ClassDef, cunit: CompilationUnit) {
      val claszSymbol = cd.symbol

      // GenASM checks this before classfiles are emitted, https://github.com/scala/scala/commit/e4d1d930693ac75d8eb64c2c3c69f2fc22bec739
      val lowercaseJavaClassName = claszSymbol.javaClassName.toLowerCase
      caseInsensitively.get(lowercaseJavaClassName) match {
        case None =>
          caseInsensitively.put(lowercaseJavaClassName, claszSymbol)
        case Some(dupClassSym) =>
          cunit.warning(
            claszSymbol.pos,
            s"Class ${claszSymbol.javaClassName} differs only in case from ${dupClassSym.javaClassName}. " +
            "Such classes will overwrite one another on case-insensitive filesystems."
          )
      }

      // -------------- mirror class, if needed --------------
      val mirrorC =
        if (isStaticModule(claszSymbol) && isTopLevelModule(claszSymbol)) {
          if (claszSymbol.companionClass == NoSymbol) {
            mirrorCodeGen.genMirrorClass(claszSymbol, cunit)
          } else {
            log(s"No mirror class for module with linked class: ${claszSymbol.fullName}");
            null
          }
        } else null

      // -------------- "plain" class --------------
      val pcb = new PlainClassBuilder(cunit)
      pcb.genPlainClass(cd)
      val outF = if (needsOutFolder) getOutFolder(claszSymbol, pcb.thisName, cunit) else null;
      val plainC = pcb.cnode

      // -------------- bean info class, if needed --------------
      val beanC =
        if (claszSymbol hasAnnotation BeanInfoAttr) {
          beanInfoCodeGen.genBeanInfoClass(
            claszSymbol, cunit,
            fieldSymbols(claszSymbol),
            methodSymbols(cd)
          )
        } else null

      // ----------- hand over to next pipeline

      val item2 =
        Item2(arrivalPos,
              mirrorC, plainC, beanC,
              outF)

      q2 add item2 // at the very end of this method so that no Worker2 thread starts mutating before we're done.

    } // end of method visit()

    /*
     *  Pipeline that takes ClassNodes from queue-2. The unit of work depends on the optimization level:
     *
     *    (a) no optimization involves:
     *          - converting the plain ClassNode to byte array and placing it on queue-3
     */
    class Worker2 {

      def run() {
        while (true) {
          val item = q2.poll
          if (item.isPoison) {
            q3 add poison3
            return
          }
          else {
            try   { addToQ3(item) }
            catch {
              case ex: Throwable =>
                ex.printStackTrace()
                error(s"Error while emitting ${item.plain.name}\n${ex.getMessage}")
            }
          }
        }
      }

      private def addToQ3(item: Item2) {

        def getByteArray(cn: asm.tree.ClassNode): Array[Byte] = {
          val cw = new CClassWriter(extraProc)
          cn.accept(cw)
          cw.toByteArray
        }

        val Item2(arrivalPos, mirror, plain, bean, outFolder) = item

        val mirrorC = if (mirror == null) null else SubItem3(mirror.name, getByteArray(mirror))
        val plainC  = SubItem3(plain.name, getByteArray(plain))
        val beanC   = if (bean == null)   null else SubItem3(bean.name, getByteArray(bean))

        q3 add Item3(arrivalPos, mirrorC, plainC, beanC, outFolder)

      }

    } // end of class BCodePhase.Worker2

    var arrivalPos = 0

    /*
     *  A run of the BCodePhase phase comprises:
     *
     *    (a) set-up steps (most notably supporting maps in `BCodeTypes`,
     *        but also "the" writer where class files in byte-array form go)
     *
     *    (b) building of ASM ClassNodes, their optimization and serialization.
     *
     *    (c) tear down (closing the classfile-writer and clearing maps)
     *
     */
    override def run() {

      arrivalPos = 0 // just in case
      scalaPrimitives.init
      initBCodeTypes()

      // initBytecodeWriter invokes fullName, thus we have to run it before the typer-dependent thread is activated.
      bytecodeWriter  = initBytecodeWriter(cleanup.getEntryPoints)
      mirrorCodeGen   = new JMirrorBuilder
      beanInfoCodeGen = new JBeanInfoBuilder

      needsOutFolder = bytecodeWriter.isInstanceOf[ClassBytecodeWriter]

      super.run()
      q2 add poison2
      (new Worker2).run()
      drainQ3()

      // closing output files.
      bytecodeWriter.close()

      caseInsensitively.clear()

      /* TODO Bytecode can be verified (now that all classfiles have been written to disk)
       *
       * (1) asm.util.CheckAdapter.verify()
       *       public static void verify(ClassReader cr, ClassLoader loader, boolean dump, PrintWriter pw)
       *     passing a custom ClassLoader to verify inter-dependent classes.
       *     Alternatively,
       *       - an offline-bytecode verifier could be used (e.g. Maxine brings one as separate tool).
       *       - -Xverify:all
       *
       * (2) if requested, check-java-signatures, over and beyond the syntactic checks in `getGenericSignature()`
       *
       */

      // clearing maps
      clearBCodeTypes()
    }

    /* Pipeline that writes classfile representations to disk. */
    private def drainQ3() {

      def sendToDisk(cfr: SubItem3, outFolder: scala.tools.nsc.io.AbstractFile) {
        if (cfr != null){
          val SubItem3(jclassName, jclassBytes) = cfr
          try {
            val outFile =
              if (outFolder == null) null
              else getFileForClassfile(outFolder, jclassName, ".class")
            bytecodeWriter.writeClass(jclassName, jclassName, jclassBytes, outFile)
          }
          catch {
            case e: FileConflictException =>
              error(s"error writing $jclassName: ${e.getMessage}")
          }
        }
      }

      var moreComing = true
      // `expected` denotes the arrivalPos whose Item3 should be serialized next
      var expected = 0

      while (moreComing) {
        val incoming = q3.poll
        moreComing   = !incoming.isPoison
        if (moreComing) {
          val item = incoming
          val outFolder = item.outFolder
          sendToDisk(item.mirror, outFolder)
          sendToDisk(item.plain,  outFolder)
          sendToDisk(item.bean,   outFolder)
          expected += 1
        }
      }

      // we're done
      assert(q2.isEmpty, s"Some classfiles remained in the second queue: $q2")
      assert(q3.isEmpty, s"Some classfiles weren't written to disk: $q3")

    }

    override def apply(cunit: CompilationUnit): Unit = {

      def gen(tree: Tree) {
        tree match {
          case EmptyTree            => ()
          case PackageDef(_, stats) => stats foreach gen
          case cd: ClassDef         =>
            visit(arrivalPos, cd, cunit)
            arrivalPos += 1
        }
      }

      gen(cunit.body)
    }

  } // end of class BCodePhase

} // end of class GenBCode
