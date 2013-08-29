import scala.tools.nsc.backend.bcode.DeadStoreElimPrim
import scala.tools.nsc.backend.bcode.Util
import scala.tools.partest.BytecodeTest
import scala.tools.asm
import scala.collection.JavaConverters._

import scala.tools.asm.Opcodes

/*
 *    (c) Elide a STORE-LOAD pair provided the local (of primitive type) isn't used afterwards.
 *        For example:
 *           9:  istore_2
 *          10:  iload_2  // no intervening LabelNode between previous instruction and this one
 *        ie there's no consumer for the STORE instruction above other than the (immediately following) LOAD.
 */
object Test extends BytecodeTest {

  def show: Unit = {
    val t   = transformed(before())
    val isa = wrapped(t)
    val isb = wrapped(after())
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
    m.visitInsn(Opcodes.ICONST_0)
    m.visitVarInsn(Opcodes.ISTORE, 1)
    m.visitVarInsn(Opcodes.ILOAD, 1)
    m.visitInsn(Opcodes.POP)

    m.visitInsn(Opcodes.RETURN)

    m
  }

  def after(): asm.tree.MethodNode = {
    val m  = mkMethodNode
    m.visitInsn(Opcodes.ICONST_0)
    m.visitInsn(Opcodes.POP)
    m.visitInsn(Opcodes.RETURN)

    m
  }

  def transformed(input: asm.tree.MethodNode): asm.tree.MethodNode = {
    val tr = new DeadStoreElimPrim
    Util.computeMaxLocalsMaxStack(input)
    do { tr.transform("C", input) } while (tr.changed)

    input
  }

}