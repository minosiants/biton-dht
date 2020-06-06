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
