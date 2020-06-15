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

package biton.dht.syntax

import java.net.{ InetAddress, UnknownHostException }

import com.comcast.ip4s.{ Hostname, IpAddress }

import cats.effect.IO

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
