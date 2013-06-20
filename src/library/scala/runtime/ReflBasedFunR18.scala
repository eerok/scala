/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2002-2013, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */
// GENERATED CODE: DO NOT EDIT. See scala.Function0 for timestamp.

package scala.runtime

final class ReflBasedFunR18[-T1, -T2, -T3, -T4, -T5, -T6, -T7, -T8, -T9, -T10, -T11, -T12, -T13, -T14, -T15, -T16, -T17, -T18, +R](delegate: java.lang.reflect.Method, receiver: Object, args: Array[Object]) extends AbstractFunction18[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, R] {

  /** Apply the body of this function to the argument{s}.
   *  @return   the result of function application.
   */
  def apply(v1: T1, v2: T2, v3: T3, v4: T4, v5: T5, v6: T6, v7: T7, v8: T8, v9: T9, v10: T10, v11: T11, v12: T12, v13: T13, v14: T14, v15: T15, v16: T16, v17: T17, v18: T18): R = {
    val cargs = args.clone()
    cargs(0) = v1.asInstanceOf[AnyRef]
    cargs(1) = v2.asInstanceOf[AnyRef]
    cargs(2) = v3.asInstanceOf[AnyRef]
    cargs(3) = v4.asInstanceOf[AnyRef]
    cargs(4) = v5.asInstanceOf[AnyRef]
    cargs(5) = v6.asInstanceOf[AnyRef]
    cargs(6) = v7.asInstanceOf[AnyRef]
    cargs(7) = v8.asInstanceOf[AnyRef]
    cargs(8) = v9.asInstanceOf[AnyRef]
    cargs(9) = v10.asInstanceOf[AnyRef]
    cargs(10) = v11.asInstanceOf[AnyRef]
    cargs(11) = v12.asInstanceOf[AnyRef]
    cargs(12) = v13.asInstanceOf[AnyRef]
    cargs(13) = v14.asInstanceOf[AnyRef]
    cargs(14) = v15.asInstanceOf[AnyRef]
    cargs(15) = v16.asInstanceOf[AnyRef]
    cargs(16) = v17.asInstanceOf[AnyRef]
    cargs(17) = v18.asInstanceOf[AnyRef]
    try {
      delegate.invoke(receiver, cargs: _*).asInstanceOf[R]
    } catch {
      case ite: java.lang.reflect.InvocationTargetException => throw ite.getCause()
    }
  }

    
}