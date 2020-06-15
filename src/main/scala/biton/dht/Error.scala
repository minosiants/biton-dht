/*
 * Copyright 2020 Kaspar Minosiants
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package biton.dht

import cats.{ Eq, Show }
import cats.data.NonEmptyList

import scala.util.control.NoStackTrace

sealed abstract class Error extends NoStackTrace with Product with Serializable

object Error {
  final case class KBucketError(msg: String)               extends Error
  final case class MultiError(errors: NonEmptyList[Error]) extends Error
  final case class ClientError(msg: String)                extends Error
  final case class Timeout(msg: String)                    extends Error
  final case class ServerError(msg: String)                extends Error
  final case class KRPCError(msg: String)                  extends Error
  final case class DHTError(msg: String)                   extends Error
  final case class ConversionError(msg: String)            extends Error
  final case class FileOpsError(msg: String, e: Throwable) extends Error

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
    case FileOpsError(msg, e) =>
      s"FileOpsError: $msg. Throwable: ${e.getClass} - ${e.getMessage}"
  }
}
