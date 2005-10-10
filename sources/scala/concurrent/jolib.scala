/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003-04, LAMP/EPFL               **
**  __\ \/ /__/ __ |/ /__/ __ |                                         **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
** $Id$
\*                                                                      */

package scala.concurrent;


/**
* Library for using join-calculus concurrent primitives in Scala.
*/
object jolib {

  type Pattern = List[Signal];

  type Rule = PartialFunction[List[Any], unit];

  /////////////////// JOIN DEFINITION /////////////////////////

  class Join {

    private var ruls: List[Pair[Pattern, Rule]] = null;

    def canMatch(p: Pattern) =
      p forall { s => !s.queue.isEmpty };

    def values(p: Pattern): List[Any] =
      p map { s => s.queue.dequeue: Any };

    def rules(rs: Pair[Pattern, Rule]*) =
      ruls = rs.asInstanceOf[List[Pair[Pattern, Rule]]];

    def tryMatch =
      (ruls find { case Pair(p, _) => canMatch(p) }) match {
        case None => () => ();
        case Some(Pair(p, r)) => {
          val args = values(p);
          () => concurrent.ops.spawn(r(args))
        }
      }

  }

  /////////////////// SIGNALS /////////////////////////

  abstract class Signal(join: Join) {
    type C;
    val queue = new collection.mutable.Queue[C];
    def tryReduction(x: C): unit = {
      val continuation = join synchronized {
        queue.enqueue(x);
        join.tryMatch
      };
      continuation()
    }
  }

  abstract class Asynchr(join: Join) extends Signal(join) {
    def apply(x: C): unit = tryReduction(x);
  }

  abstract class Synchr[a](join: Join) extends Signal(join) {
    type C <: SyncVar[a];
    def apply(x: C): a = {
      tryReduction(x);
      x.get
    }
  }

}

