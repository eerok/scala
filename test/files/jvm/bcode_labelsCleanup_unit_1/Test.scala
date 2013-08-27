import scala.tools.nsc.backend.asm.LabelsCleanup
import scala.tools.partest.BytecodeTest
import scala.tools.asm
import scala.collection.JavaConverters._

import scala.tools.asm.Opcodes

object Test extends BytecodeTest {

  def show: Unit = {
    val ma  = transformed(before())
    val mb  = after()
    val isa = wrapped(ma)
    val isb = wrapped(mb)
    // redundant LineNumberNodes are removed
    assert(isa == isb)
  }

  def wrapped(m: asm.tree.MethodNode) = instructions.fromMethod(m)

  def mkMethodNode = {
    new asm.tree.MethodNode(
      Opcodes.ACC_PUBLIC,
      "m",
      "()V",
      null, null
    )
  }

  def before(): asm.tree.MethodNode = {
    val m  = mkMethodNode
    val L = new asm.Label
    //     return
    m.visitInsn(Opcodes.RETURN)
    // line number 1
    m.visitLineNumber(1, L)
    // line number 2
    m.visitLineNumber(2, L)
    // L:
    m.visitLabel(L)

    m
  }

  def after(): asm.tree.MethodNode = {
    val m  = mkMethodNode
    //     return
    m.visitInsn(Opcodes.RETURN)

    m
  }

  def transformed(input: asm.tree.MethodNode): asm.tree.MethodNode = {
    val tr = new LabelsCleanup
    do { tr.transform(input) } while (tr.changed)

    input
  }

}
