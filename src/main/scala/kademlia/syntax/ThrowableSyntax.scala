package kademlia
package syntax
import cats.syntax.show._

trait ThrowableSyntax {
  implicit def kadThrowableSyntax(error: Throwable) = new ThrowableOps(error)
}

final class ThrowableOps(val error: Throwable) extends AnyVal {
  def string: String = error match {
    case e: Error => s"Error: ${e.show}"
    case e: Throwable =>
      val msg = if (e.getMessage != null) s"message: ${e.getMessage}" else ""
      s"Error: $e $msg"

  }
}
