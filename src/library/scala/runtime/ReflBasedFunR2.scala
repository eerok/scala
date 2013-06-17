/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2002-2013, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */
// GENERATED CODE: DO NOT EDIT. See scala.Function0 for timestamp.

package scala.runtime

final class ReflBasedFunR2[-T1, -T2, +R](delegate: java.lang.reflect.Method, receiver: Object, args: Array[Object]) extends AbstractFunction2[T1, T2, R] {

  /** Apply the body of this function to the argument{s}.
   *  @return   the result of function application.
   */
  def apply(v1: T1, v2: T2): R = {
    args(0) = v1.asInstanceOf[AnyRef]
    args(1) = v2.asInstanceOf[AnyRef]
    try {
      delegate.invoke(receiver, args: _*).asInstanceOf[R]
    } catch {
      case ita: java.lang.reflect.InvocationTargetException => throw ita.getCause()
    }
  }

    
}
