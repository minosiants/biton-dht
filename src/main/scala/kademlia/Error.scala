package kademlia

import cats.Eq
import cats.data.NonEmptyList

import scala.util.control.NoStackTrace

sealed abstract class Error extends NoStackTrace with Product with Serializable

object Error {
  final case class KBucketError(msg: String)               extends Error
  final case class MultiError(errors: NonEmptyList[Error]) extends Error
  final case class ClientError(msg: String)                extends Error
  final case class ServerError(msg: String)                extends Error
  implicit val kerrorEq: Eq[Error] = Eq.fromUniversalEquals
}
