package biton.dht

import cats.{ Eq, Show }
import cats.data.NonEmptyList

import scala.util.control.NoStackTrace

sealed abstract class Error extends NoStackTrace with Product with Serializable

object Error {
  final case class KBucketError(msg: String)                extends Error
  final case class MultiError(errors: NonEmptyList[Error])  extends Error
  final case class ClientError(msg: String)                 extends Error
  final case class Timeout(msg: String)                     extends Error
  final case class ServerError(msg: String)                 extends Error
  final case class KRPCError(msg: String)                   extends Error
  final case class DHTError(msg: String)                    extends Error
  final case class ConversionError(msg: String)             extends Error
  final case class FileOperation(msg: String, e: Throwable) extends Error

  implicit val eqKerror: Eq[Error] = Eq.fromUniversalEquals

  implicit val showKerror: Show[Error] = Show.show {
    case KBucketError(msg)    => s"KBucketError: $msg"
    case MultiError(errors)   => s"MultiError: $errors"
    case ClientError(msg)     => s"ClientError: $msg"
    case KRPCError(msg)       => s"KRPCError: $msg"
    case Timeout(msg)         => s"Timeout: $msg"
    case ServerError(msg)     => s"ServerError: $msg"
    case DHTError(msg)        => s"DHTError: $msg"
    case ConversionError(msg) => s"ConversionError: $msg"
  }
}
