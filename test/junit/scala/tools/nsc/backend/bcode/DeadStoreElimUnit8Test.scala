package scala.tools.nsc.backend.bcode

import org.junit.Assert._
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

import scala.tools.asm
import scala.collection.JavaConverters._

import scala.tools.asm.Opcodes

/*
 *   (c) focus on ASTORE X for X != 0 in instanceMethod, where X goes unread, where RHS is FakeParamLoad(0)
 *       Note: FakeParamLoad(0) and not ALOAD_0, the latter could give us sthg other than FakeParamLoad(0)
 *       Subcases:
 *       ...
*       (c.4) LHS slot contains some other        ===> drop; ACONST_NULL; store
 */
@RunWith(classOf[JUnit4])
class DeadStoreElimUnit8Test {

  @Test
  def show: Unit = {
    val t   = transformed(before())
    val isa = wrapped(t)
    val isb = wrapped(after())
    assert(isa == isb)
  }

  def wrapped(m: asm.tree.MethodNode) = {
    Util.computeMaxLocalsMaxStack(m)
    Util.textify(m)
  }

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
    m.visitFieldInsn(Opcodes.GETSTATIC, "scala/Predef$", "MODULE$", "Lscala/Predef$;")
    m.visitVarInsn(Opcodes.ASTORE, 1)
    m.visitVarInsn(Opcodes.ALOAD, 0)
    m.visitVarInsn(Opcodes.ASTORE, 1)
    m.visitInsn(Opcodes.RETURN)

    m
  }

  def after(): asm.tree.MethodNode = {
    val m  = mkMethodNode
    m.visitFieldInsn(Opcodes.GETSTATIC, "scala/Predef$", "MODULE$", "Lscala/Predef$;")
    m.visitVarInsn(Opcodes.ASTORE, 1)
    m.visitVarInsn(Opcodes.ALOAD, 0)
    m.visitInsn(Opcodes.POP)
    m.visitInsn(Opcodes.ACONST_NULL)
    m.visitVarInsn(Opcodes.ASTORE, 1)
    m.visitInsn(Opcodes.RETURN)

    m
  }

  def transformed(input: asm.tree.MethodNode): asm.tree.MethodNode = {
    val tr = new DeadStoreElimRef
    Util.computeMaxLocalsMaxStack(input)
    do { tr.transform("C", input) } while (tr.changed)

    input
  }

}