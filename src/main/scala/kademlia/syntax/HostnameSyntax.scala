package kademlia.syntax

import java.net.{InetAddress, UnknownHostException}

import cats.effect.IO
import com.comcast.ip4s.{Hostname, IpAddress}

//Remove when next version of ip4s is released

trait HostnameSyntax {
  implicit def hostnameSyntax(host: Hostname): HostnameOps =
    new HostnameOps(host)
}

final class HostnameOps(host: Hostname) {

  def resolve: IO[Option[IpAddress]] = {
    IO {
      try {
        val addr = InetAddress.getByName(host.toString)
        IpAddress.fromBytes(addr.getAddress)
      } catch {
        case _: UnknownHostException => None
      }
    }
  }

}
