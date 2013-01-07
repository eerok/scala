/* NSC -- new Scala compiler
 * Copyright 2005-2013 LAMP/EPFL
 * @author  Martin Odersky
 */

package scala.tools.nsc

import util.FreshNameCreator
import scala.reflect.internal.util.{ SourceFile, NoSourceFile }
import scala.collection.mutable
import scala.collection.mutable.{ LinkedHashSet, ListBuffer }

trait CompilationUnits { self: Global =>

  /** An object representing a missing compilation unit.
   */
  object NoCompilationUnit extends CompilationUnit(NoSourceFile) {
    override lazy val isJava = false
    override def exists = false
    override def toString() = "NoCompilationUnit"
  }

  /** One unit of compilation that has been submitted to the compiler.
    * It typically corresponds to a single file of source code.  It includes
    * error-reporting hooks.  */
  class CompilationUnit(val source: SourceFile) extends CompilationUnitContextApi { self =>

    /** the fresh name creator */
    val fresh: FreshNameCreator = new FreshNameCreator.Default

    def freshTermName(prefix: String): TermName = newTermName(fresh.newName(prefix))
    def freshTypeName(prefix: String): TypeName = newTypeName(fresh.newName(prefix))

    /** the content of the compilation unit in tree form */
    var body: Tree = EmptyTree

    def exists = source != NoSourceFile && source != null

    /** Note: depends now contains toplevel classes.
     *  To get their sourcefiles, you need to dereference with .sourcefile
     */
    private[this] val _depends = mutable.HashSet[Symbol]()
    // SBT compatibility (SI-6875)
    //
    // imagine we have a file named A.scala, which defines a trait named Foo and a module named Main
    // Main contains a call to a macro, which calls c.introduceTopLevel to define a mock for Foo
    // c.introduceTopLevel creates a virtual file Virt35af32.scala, which contains a class named FooMock extending Foo,
    // and macro expansion instantiates FooMock. the stage is now set. let's see what happens next.
    //
    // without this workaround in scalac or without being patched itself, sbt will think that
    // * Virt35af32 depends on A (because it extends Foo from A)
    // * A depends on Virt35af32 (because it contains a macro expansion referring to FooMock from Virt35af32)
    //
    // after compiling A.scala, SBT will notice that it has a new source file named Virt35af32.
    // it will also think that this file hasn't yet been compiled and since A depends on it
    // it will think that A needs to be recompiled.
    //
    // recompilation will lead to another macro expansion. that another macro expansion might choose to create a fresh mock,
    // producing another virtual file, say, Virtee509a, which will again trick SBT into thinking that A needs a recompile,
    // which will lead to another macro expansion, which will produce another virtual file and so on
    def depends = if (exists && !source.file.isVirtual) _depends else mutable.HashSet[Symbol]()

    /** so we can relink
     */
    private[this] val _defined = mutable.HashSet[Symbol]()
    def defined = if (exists && !source.file.isVirtual) _defined else mutable.HashSet[Symbol]()

    /** Synthetic definitions generated by namer, eliminated by typer.
     */
    object synthetics {
      private val map = mutable.HashMap[Symbol, Tree]()
      def update(sym: Symbol, tree: Tree) {
        debuglog(s"adding synthetic ($sym, $tree) to $self")
        map.update(sym, tree)
      }
      def -=(sym: Symbol) {
        debuglog(s"removing synthetic $sym from $self")
        map -= sym
      }
      def get(sym: Symbol): Option[Tree] = logResultIf[Option[Tree]](s"found synthetic for $sym in $self", _.isDefined) {
        map get sym
      }
      def keys: Iterable[Symbol] = map.keys
      def clear(): Unit = map.clear()
      override def toString = map.toString
    }

    /** things to check at end of compilation unit */
    val toCheck = new ListBuffer[() => Unit]

    /** The features that were already checked for this unit */
    var checkedFeatures = Set[Symbol]()

    def position(pos: Int) = source.position(pos)

    /** The position of a targeted type check
     *  If this is different from NoPosition, the type checking
     *  will stop once a tree that contains this position range
     *  is fully attributed.
     */
    def targetPos: Position = NoPosition

    /** The icode representation of classes in this compilation unit.
     *  It is empty up to phase 'icode'.
     */
    val icode: LinkedHashSet[icodes.IClass] = new LinkedHashSet

    def echo(pos: Position, msg: String) =
      reporter.echo(pos, msg)

    def error(pos: Position, msg: String) =
      reporter.error(pos, msg)

    def warning(pos: Position, msg: String) =
      reporter.warning(pos, msg)

    def deprecationWarning(pos: Position, msg: String) =
      currentRun.deprecationWarnings0.warn(pos, msg)

    def uncheckedWarning(pos: Position, msg: String) =
      currentRun.uncheckedWarnings0.warn(pos, msg)

    def inlinerWarning(pos: Position, msg: String) =
      currentRun.inlinerWarnings.warn(pos, msg)

    def incompleteInputError(pos: Position, msg:String) =
      reporter.incompleteInputError(pos, msg)

    def comment(pos: Position, msg: String) =
      reporter.comment(pos, msg)

    /** Is this about a .java source file? */
    lazy val isJava = source.file.name.endsWith(".java")

    override def toString() = source.toString()
  }
}
