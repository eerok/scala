/* NSC -- new Scala compiler
 * Copyright 2005-2012 LAMP/EPFL
 * @author  Martin Odersky
 */

package scala
package tools.nsc
package backend.jvm

import scala.tools.asm
import scala.annotation.switch
import scala.collection.{ immutable, mutable }

/*
 *  Immutable representations of bytecode-level types.
 *
 *  @author  Miguel Garcia, http://lamp.epfl.ch/~magarcia/ScalaCompilerCornerReloaded
 *  @version 1.0
 *
 */
abstract class BCodeGlue extends SubComponent {

  import global._

  implicit val bct = this

  val BT_ZERO = newBType(0, 0, 0)

  final def newBType(sort: Int, off: Int, len: Int): BType = {
    val hiPart = (((sort << 24) | len).asInstanceOf[Long] << 32)
    val loPart = off.asInstanceOf[Long]
    new BType(hiPart | loPart)
  }

  object BT {

    import global.chrs

    // ------------- primitive types -------------

    val VOID_TYPE    = newBType(asm.Type.VOID,    ('V' << 24) | (5 << 16) | (0 << 8) | 0, 1)
    val BOOLEAN_TYPE = newBType(asm.Type.BOOLEAN, ('Z' << 24) | (0 << 16) | (5 << 8) | 1, 1)
    val CHAR_TYPE    = newBType(asm.Type.CHAR,    ('C' << 24) | (0 << 16) | (6 << 8) | 1, 1)
    val BYTE_TYPE    = newBType(asm.Type.BYTE,    ('B' << 24) | (0 << 16) | (5 << 8) | 1, 1)
    val SHORT_TYPE   = newBType(asm.Type.SHORT,   ('S' << 24) | (0 << 16) | (7 << 8) | 1, 1)
    val INT_TYPE     = newBType(asm.Type.INT,     ('I' << 24) | (0 << 16) | (0 << 8) | 1, 1)
    val FLOAT_TYPE   = newBType(asm.Type.FLOAT,   ('F' << 24) | (2 << 16) | (2 << 8) | 1, 1)
    val LONG_TYPE    = newBType(asm.Type.LONG,    ('J' << 24) | (1 << 16) | (1 << 8) | 2, 1)
    val DOUBLE_TYPE  = newBType(asm.Type.DOUBLE,  ('D' << 24) | (3 << 16) | (3 << 8) | 2, 1)

    /*
     * Returns the Java type corresponding to the given type descriptor.
     *
     * @param off the offset of this descriptor in the chrs buffer.
     * @return the Java type corresponding to the given type descriptor.
     *
     * can-multi-thread
     */
    def getType(off: Int): BType = {
      var len = 0
      chrs(off) match {
        case 'V' => VOID_TYPE
        case 'Z' => BOOLEAN_TYPE
        case 'C' => CHAR_TYPE
        case 'B' => BYTE_TYPE
        case 'S' => SHORT_TYPE
        case 'I' => INT_TYPE
        case 'F' => FLOAT_TYPE
        case 'J' => LONG_TYPE
        case 'D' => DOUBLE_TYPE
        case '[' =>
          len = 1
          while (chrs(off + len) == '[') {
            len += 1
          }
          if (chrs(off + len) == 'L') {
            len += 1
            while (chrs(off + len) != ';') {
              len += 1
            }
          }
          getCanonical(asm.Type.ARRAY, off, len + 1)
        case 'L' =>
          len = 1
          while (chrs(off + len) != ';') {
            len += 1
          }
          getCanonical(asm.Type.OBJECT, off + 1, len - 1)
        // case '(':
        case _ =>
          assert(chrs(off) == '(')
          var resPos = off + 1
          while (chrs(resPos) != ')') { resPos += 1 }
          val resType = getType(resPos + 1)
          val len = resPos - off + 1 + resType.len;
          getCanonical(
            asm.Type.METHOD,
            off,
            if (resType.hasObjectSort) {
              len + 2 // "+ 2" accounts for the "L ... ;" in a descriptor for a non-array reference.
            } else {
              len
            }
          )
      }
    }

    private def getCanonical(sort: Int, off: Int, len: Int): BType = {
      val ca  = _root_.java.util.Arrays.copyOfRange(chrs, off, off + len)
      assert(new String(chrs, off, len) == new String(ca))  // TODO debug
      val n = global.newTypeName(ca)
      // this time we've fielded on the canonical offset.
      newBType(sort, n.start, n.length)
    }

    /* Params denote an internal name.
     *  can-multi-thread
     */
    def getObjectType(index: Int, length: Int): BType = {
      val sort = if (chrs(index) == '[') asm.Type.ARRAY else asm.Type.OBJECT;
      newBType(sort, index, length)
    }

    /*
     * @param typeDescriptor a field or method type descriptor.
     *
     * must-single-thread
     */
    def getType(typeDescriptor: String): BType = {
      val n = global.newTypeName(typeDescriptor)
      getType(n.start)
    }

    /*
     * @param methodDescriptor a method descriptor.
     *
     * must-single-thread
     */
    def getMethodType(methodDescriptor: String): BType = {
      val n = global.newTypeName(methodDescriptor)
      newBType(asm.Type.METHOD, n.start, n.length) // TODO assert isValidMethodDescriptor
    }

    /*
     * Returns the Java method type corresponding to the given argument and return types.
     *
     * @param returnType the return type of the method.
     * @param argumentTypes the argument types of the method.
     * @return the Java type corresponding to the given argument and return types.
     *
     * must-single-thread
     */
    def getMethodType(returnType: BType, argumentTypes: Array[BType]): BType = {
      val n = global.newTypeName(getMethodDescriptor(returnType, argumentTypes))
      newBType(asm.Type.METHOD, n.start, n.length)
    }

    /*
     * Returns the Java types corresponding to the argument types of method descriptor whose first argument starts at idx0.
     *
     * @param idx0 index into chrs of the first argument.
     * @return the Java types corresponding to the argument types of the given method descriptor.
     *
     * can-multi-thread
     */
    def getArgumentTypes(idx0: Int): Array[BType] = {
      assert(chrs(idx0 - 1) == '(', "doesn't look like a method descriptor.")
      val args = new Array[BType](getArgumentCount(idx0))
      var off = idx0
      var size = 0
      while (chrs(off) != ')') {
        args(size) = getType(off)
        off += args(size).len
        if (args(size).sort == asm.Type.OBJECT) { off += 2 }
        // debug: assert("LVZBSCIJFD[)".contains(chrs(off)))
        size += 1
      }
      // debug: var check = 0; while (check < args.length) { assert(args(check) != null); check += 1 }
      args
    }

    /*
     * Returns the Java types corresponding to the argument types of the given
     * method descriptor.
     *
     * @param methodDescriptor a method descriptor.
     * @return the Java types corresponding to the argument types of the given method descriptor.
     *
     * must-single-thread
     */
    def getArgumentTypes(methodDescriptor: String): Array[BType] = {
      val n = global.newTypeName(methodDescriptor)
      getArgumentTypes(n.start + 1)
    }

    /*
     * Returns the number of argument types of this method type, whose first argument starts at idx0.
     *
     * @param idx0 index into chrs of the first argument.
     * @return the number of argument types of this method type.
     *
     * can-multi-thread
     */
    def getArgumentCount(idx0: Int): Int = {
      assert(chrs(idx0 - 1) == '(', "doesn't look like a method descriptor.")
      var off  = idx0
      var size = 0
      var keepGoing = true
      while (keepGoing) {
        val car = chrs(off)
        off += 1
        if (car == ')') {
          keepGoing = false
        } else if (car == 'L') {
          while (chrs(off) != ';') { off += 1 }
          off += 1
          size += 1
        } else if (car != '[') {
          size += 1
        }
      }

      size
    }

    /*
     * Returns the Java type corresponding to the return type of the given
     * method descriptor.
     *
     * @param methodDescriptor a method descriptor.
     * @return the Java type corresponding to the return type of the given method descriptor.
     *
     * must-single-thread
     */
    def getReturnType(methodDescriptor: String): BType = {
      val n     = global.newTypeName(methodDescriptor)
      val delta = n.pos(')') // `delta` is relative to the Name's zero-based start position, not a valid index into chrs.
      assert(delta < n.length, s"not a valid method descriptor: $methodDescriptor")
      getType(n.start + delta + 1)
    }

    /*
     * Returns the descriptor corresponding to the given argument and return types.
     * Note: no BType is created here for the resulting method descriptor,
     *       if that's desired the invoker is responsible for that.
     *
     * @param returnType the return type of the method.
     * @param argumentTypes the argument types of the method.
     * @return the descriptor corresponding to the given argument and return types.
     *
     * can-multi-thread
     */
    def getMethodDescriptor(
        returnType: BType,
        argumentTypes: Array[BType]): String =
    {
      val buf = new StringBuffer()
      buf.append('(')
      var i = 0
      while (i < argumentTypes.length) {
        argumentTypes(i).getDescriptor(buf)
        i += 1
      }
      buf.append(')')
      returnType.getDescriptor(buf)
      buf.toString()
    }

  } // end of object BT

  /*
   * Creates a TypeName and the BType token for it.
   * This method does not add to `innerClassBufferASM`, use `internalName()` or `asmType()` or `toTypeKind()` for that.
   *
   * must-single-thread
   */
  def brefType(iname: String): BType = { brefType(newTypeName(iname.toCharArray(), 0, iname.length())) }

  /*
   * Creates a BType token for the TypeName received as argument.
   * This method does not add to `innerClassBufferASM`, use `internalName()` or `asmType()` or `toTypeKind()` for that.
   *
   *  can-multi-thread
   */
  def brefType(iname: TypeName): BType = BT.getObjectType(iname.start, iname.length)

  // due to keyboard economy only
  val UNIT   = BT.VOID_TYPE
  val BOOL   = BT.BOOLEAN_TYPE
  val CHAR   = BT.CHAR_TYPE
  val BYTE   = BT.BYTE_TYPE
  val SHORT  = BT.SHORT_TYPE
  val INT    = BT.INT_TYPE
  val LONG   = BT.LONG_TYPE
  val FLOAT  = BT.FLOAT_TYPE
  val DOUBLE = BT.DOUBLE_TYPE

  val BOXED_UNIT    = brefType("java/lang/Void")
  val BOXED_BOOLEAN = brefType("java/lang/Boolean")
  val BOXED_BYTE    = brefType("java/lang/Byte")
  val BOXED_SHORT   = brefType("java/lang/Short")
  val BOXED_CHAR    = brefType("java/lang/Character")
  val BOXED_INT     = brefType("java/lang/Integer")
  val BOXED_LONG    = brefType("java/lang/Long")
  val BOXED_FLOAT   = brefType("java/lang/Float")
  val BOXED_DOUBLE  = brefType("java/lang/Double")

  /*
   * RT_NOTHING and RT_NULL exist at run-time only.
   * They are the bytecode-level manifestation (in method signatures only) of what shows up as NothingClass resp. NullClass in Scala ASTs.
   * Therefore, when RT_NOTHING or RT_NULL are to be emitted,
   * a mapping is needed: the internal names of NothingClass and NullClass can't be emitted as-is.
   */
  val RT_NOTHING = brefType("scala/runtime/Nothing$")
  val RT_NULL    = brefType("scala/runtime/Null$")
  val CT_NOTHING = brefType("scala/Nothing") // TODO needed?
  val CT_NULL    = brefType("scala/Null")    // TODO needed?

  val srBooleanRef = brefType("scala/runtime/BooleanRef")
  val srByteRef    = brefType("scala/runtime/ByteRef")
  val srCharRef    = brefType("scala/runtime/CharRef")
  val srIntRef     = brefType("scala/runtime/IntRef")
  val srLongRef    = brefType("scala/runtime/LongRef")
  val srFloatRef   = brefType("scala/runtime/FloatRef")
  val srDoubleRef  = brefType("scala/runtime/DoubleRef")

  /*  Map from type kinds to the Java reference types.
   *  Useful when pushing class literals onto the operand stack (ldc instruction taking a class literal).
   *  @see Predef.classOf
   *  @see genConstant()
   */
  val classLiteral = immutable.Map[BType, BType](
    UNIT   -> BOXED_UNIT,
    BOOL   -> BOXED_BOOLEAN,
    BYTE   -> BOXED_BYTE,
    SHORT  -> BOXED_SHORT,
    CHAR   -> BOXED_CHAR,
    INT    -> BOXED_INT,
    LONG   -> BOXED_LONG,
    FLOAT  -> BOXED_FLOAT,
    DOUBLE -> BOXED_DOUBLE
  )

  case class MethodNameAndType(mname: String, mdesc: String)

  val asmBoxTo: Map[BType, MethodNameAndType] = {
    Map(
      BOOL   -> MethodNameAndType("boxToBoolean",   "(Z)Ljava/lang/Boolean;"  ) ,
      BYTE   -> MethodNameAndType("boxToByte",      "(B)Ljava/lang/Byte;"     ) ,
      CHAR   -> MethodNameAndType("boxToCharacter", "(C)Ljava/lang/Character;") ,
      SHORT  -> MethodNameAndType("boxToShort",     "(S)Ljava/lang/Short;"    ) ,
      INT    -> MethodNameAndType("boxToInteger",   "(I)Ljava/lang/Integer;"  ) ,
      LONG   -> MethodNameAndType("boxToLong",      "(J)Ljava/lang/Long;"     ) ,
      FLOAT  -> MethodNameAndType("boxToFloat",     "(F)Ljava/lang/Float;"    ) ,
      DOUBLE -> MethodNameAndType("boxToDouble",    "(D)Ljava/lang/Double;"   )
    )
  }

  val asmUnboxTo: Map[BType, MethodNameAndType] = {
    Map(
      BOOL   -> MethodNameAndType("unboxToBoolean", "(Ljava/lang/Object;)Z") ,
      BYTE   -> MethodNameAndType("unboxToByte",    "(Ljava/lang/Object;)B") ,
      CHAR   -> MethodNameAndType("unboxToChar",    "(Ljava/lang/Object;)C") ,
      SHORT  -> MethodNameAndType("unboxToShort",   "(Ljava/lang/Object;)S") ,
      INT    -> MethodNameAndType("unboxToInt",     "(Ljava/lang/Object;)I") ,
      LONG   -> MethodNameAndType("unboxToLong",    "(Ljava/lang/Object;)J") ,
      FLOAT  -> MethodNameAndType("unboxToFloat",   "(Ljava/lang/Object;)F") ,
      DOUBLE -> MethodNameAndType("unboxToDouble",  "(Ljava/lang/Object;)D")
    )
  }

  /*
   *  can-multi-thread
   */
  def toBType(t: asm.Type): BType = {
    (t.getSort: @switch) match {
      case asm.Type.VOID    => BT.VOID_TYPE
      case asm.Type.BOOLEAN => BT.BOOLEAN_TYPE
      case asm.Type.CHAR    => BT.CHAR_TYPE
      case asm.Type.BYTE    => BT.BYTE_TYPE
      case asm.Type.SHORT   => BT.SHORT_TYPE
      case asm.Type.INT     => BT.INT_TYPE
      case asm.Type.FLOAT   => BT.FLOAT_TYPE
      case asm.Type.LONG    => BT.LONG_TYPE
      case asm.Type.DOUBLE  => BT.DOUBLE_TYPE
      case asm.Type.ARRAY   |
           asm.Type.OBJECT  |
           asm.Type.METHOD  =>
        // TODO confirm whether this also takes care of the phantom types.
        val key =
          if (t.getSort == asm.Type.METHOD) t.getDescriptor
          else t.getInternalName

        val n = global.lookupTypeName(key.toCharArray)
        newBType(t.getSort, n.start, n.length)
    }
  }

  /*
   * ASM trees represent types as strings (internal names, descriptors).
   * Given that we operate instead on BTypes, conversion is needed when visiting MethodNodes outside GenBCode.
   *
   * can-multi-thread
   */
  def descrToBType(typeDescriptor: String): BType = {
    val c: Char = typeDescriptor(0)
    c match {
      case 'V' => BT.VOID_TYPE
      case 'Z' => BT.BOOLEAN_TYPE
      case 'C' => BT.CHAR_TYPE
      case 'B' => BT.BYTE_TYPE
      case 'S' => BT.SHORT_TYPE
      case 'I' => BT.INT_TYPE
      case 'F' => BT.FLOAT_TYPE
      case 'J' => BT.LONG_TYPE
      case 'D' => BT.DOUBLE_TYPE
      case 'L' =>
        val iname = typeDescriptor.substring(1, typeDescriptor.length() - 1)
        val n = global.lookupTypeName(iname.toCharArray)
        newBType(asm.Type.OBJECT, n.start, n.length)
      case _   =>
        val n = global.lookupTypeName(typeDescriptor.toCharArray)
        BT.getType(n.start)
    }
  }

  /*
   * Use only to lookup reference types, otherwise use `descrToBType()`
   *
   * can-multi-thread
   */
  def lookupRefBType(iname: String): BType = {
    val n    = global.lookupTypeName(iname.toCharArray)
    val sort = if (chrs(n.start) == '[') asm.Type.ARRAY else asm.Type.OBJECT;
    newBType(sort, n.start, n.length)
  }

  def lookupRefBTypeIfExisting(iname: String): BType = {
    val n    = global.lookupTypeNameIfExisting(iname.toCharArray, false)
    if (n == null) { return BT_ZERO }
    val sort = if (chrs(n.start) == '[') asm.Type.ARRAY else asm.Type.OBJECT;
    newBType(sort, n.start, n.length)
  }

}
