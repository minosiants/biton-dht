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

import java.nio.file.Path

import cats.effect._
import cats.syntax.either._
import cats.syntax.show._
import fs2.io.udp.SocketGroup
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import protocol.InfoHash
import types.NodeId
import scodec.bits._
class DHTSpec extends KSuite with TableFunctions {

  val base   = Path.of(s"target/.biton")
  val nodeId = NodeId.fromBigInt(1000000000)

  test("bootstrap") {

    val bs = Blocker[IO]
      .use { blocker =>
        SocketGroup[IO](blocker).use { sg =>
          for {
            dht <- DHT.bootstrap(
              sg,
              Conf.default().setNodeId(nodeId).setSaveTableDir(base)
            )
            table <- dht.table
          } yield table
        }
      }

    def saveTable(table: Table): IO[Path] =
      TableSerialization.toFile(base, table)

    val res = (for {
      table <- bs
      _     <- saveTable(table)
    } yield table).attempt.unsafeRunSync()

    res.leftMap {
      case e: Error => println(e.show)
    }
    println(res.map(_.nodeId.value.toBin))
    println("--")
    println(
      res.map(
        _.kbuckets.head.nodes.value
          .map(_.node.nodeId.value.toBin)
          .mkString("\n")
      )
    )
    true
  }

  def withDHT[A](nodeId: NodeId)(f: DHT => IO[A]): IO[A] = {
    Blocker[IO]
      .use { blocker =>
        SocketGroup[IO](blocker).use { sg =>
          for {
            dht <- DHT
              .fromTable(
                sg,
                Conf.default().setSaveTableDir(base).setNodeId(nodeId)
              )
            res <- f(dht)
          } yield res
        }
      }
  }

  test("lookup".only) {
    val bits     = hex"01c8c9ea65fe48a0bb02127c898bef9644b99fe0"
    val infoHash = InfoHash(bits.bits)
    val peers = withDHT(nodeId) { dht =>
      dht.lookup(infoHash).concurrently(dht.start()).compile.toList
    }

    val result = peers.unsafeRunSync()
    println(result)
    println(result.size)
    true
  }

}

object DHTSpec {
  val logger = Slf4jLogger.getLogger[IO]
}
