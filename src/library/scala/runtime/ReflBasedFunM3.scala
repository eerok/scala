/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2002-2013, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */
// GENERATED CODE: DO NOT EDIT. See scala.Function0 for timestamp.

package scala.runtime

final class ReflBasedFunM3[-T1, -T2, -T3, +R](delegate: java.lang.reflect.Method, args: Array[Object]) extends AbstractFunction3[T1, T2, T3, R] {

  /** Apply the body of this function to the argument{s}.
   *  @return   the result of function application.
   */
  def apply(v1: T1, v2: T2, v3: T3): R = {
    val cargs = args.clone()
    cargs(1) = v1.asInstanceOf[AnyRef]
    cargs(2) = v2.asInstanceOf[AnyRef]
    cargs(3) = v3.asInstanceOf[AnyRef]
    try {
      delegate.invoke(null, cargs: _*).asInstanceOf[R]
    } catch {
      case ite: java.lang.reflect.InvocationTargetException => throw ite.getCause()
    }
  }

    
}