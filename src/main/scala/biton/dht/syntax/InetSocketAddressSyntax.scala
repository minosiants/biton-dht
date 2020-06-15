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
package syntax

import java.net.InetSocketAddress

import biton.dht.Result
import biton.dht.protocol.Peer
import biton.dht.types.Contact
import com.comcast.ip4s.{ IpAddress, Port }
import cats.syntax.either._

import scala.util.Try

trait InetSocketAddressSyntax {
  implicit def inentSocketAddressSyntax(address: InetSocketAddress) =
    new InetSocketAddressOps(address)
}

final class InetSocketAddressOps(val address: InetSocketAddress)
    extends AnyVal {

  import InetSocketAddressOps._

  def toContact: Result[Contact] =
    convert[Contact](address, Contact.apply)

  def toPeer(): Result[Peer] =
    convert[Peer](address, Peer.apply)

  def toPeer(port: Port): Result[Peer] =
    toPeer().map(_.copy(port = port))

}

object InetSocketAddressOps {

  def convert[A](
      address: InetSocketAddress,
      f: (IpAddress, Port) => A
  ): Result[A] = {
    def error(msg: String): Result[A] =
      Error
        .ConversionError(s"Can not convert InetSocketAddress ($address). $msg")
        .asLeft[A]
    Try(
      for {
        ip   <- IpAddress.fromBytes(address.getAddress.getAddress)
        port <- Port(address.getPort)
      } yield f(ip, port)
    ).fold(err => error(err.toString), _.fold(error(""))(_.asRight[Error]))
  }
}
