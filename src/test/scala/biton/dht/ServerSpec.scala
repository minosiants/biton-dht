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

import biton.dht.Conf.{ GoodDuration, SecretExpiration }
import biton.dht.protocol.KMessage.NodeIdResponse
import biton.dht.protocol.{ KMessage, Transaction }
import biton.dht.types._
import cats.effect.{ Blocker, IO }
import cats.instances.list._
import cats.syntax.either._
import cats.syntax.show._
import cats.syntax.traverse._
import com.comcast.ip4s.{ IpAddress, Port }
import fs2._
import fs2.io.udp.SocketGroup
import org.scalacheck.Gen
import scodec.bits.BitVector

import scala.concurrent.duration._

class ServerSpec extends KSuite {

  val serverNode = (for {
    ip   <- IpAddress("127.0.0.1")
    port <- Port(2222)
  } yield Node(NodeId.fromBigInt(2), Contact(ip, port))).get

  def kbucket(to: Int = 30): KBucket =
    kbucketGen(Prefix(0), Prefix(BigInt(to)), KSize(10), 10).sample.get

  def tableState(pingClient: Client.Ping): IO[TableState] = {
    val nodes = kbucket().nodes.filterNot(serverNode).value.toList.map(_.node)
    for {
      ts <- TableState.empty(
        serverNode.nodeId,
        pingClient,
        GoodDuration(1.minute)
      )
      _ <- ts.addNodes(nodes)
    } yield ts
  }
  val clientNodeId = nodeIdCharGen.sample.get

  val infoHash               = infoHashGen.sample.get
  val peers                  = Gen.nonEmptyListOf(peerGen).sample.get
  val transaction            = Transaction(BitVector.fromInt(1))
  implicit val constantTrans = Random.instance(transaction)
  def sendToServer[A](f: Client => Stream[IO, A]): IO[A] =
    Blocker[IO]
      .use { blocker =>
        SocketGroup[IO](blocker).use { sg =>
          val client = Client(clientNodeId, sg)
          for {
            secrets <- Secrets.create(SecretExpiration(100.millis))
            table   <- tableState(client)
            store   <- PeerStore.inmemory()
            _       <- peers.traverse(v => store.add(infoHash, v))
            server = Server(
              serverNode.nodeId,
              table,
              store,
              secrets,
              sg,
              serverNode.contact.port
            )
            rs <- f(client)
              .delayBy(1.second)
              .concurrently(server.start())
              .compile
              .toList
              .map(_.head)
          } yield rs

        }
      }

  test("ping") {

    val result = sendToServer(_.ping(serverNode)).unsafeRunSync()
    assertEquals(result, NodeIdResponse(transaction, serverNode.nodeId))

  }

  test("findNode") {

    val response = sendToServer(
      _.findNode(serverNode.contact, serverNode.nodeId)
    ).unsafeRunSync()

    assert(response.size == 8)
    assert(ordered(response, serverNode.nodeId))
  }

  test("getPeers") {
    val response = sendToServer(
      _.getPeers(serverNode, infoHash)
    ).unsafeRunSync()
    assert(response.peers.size == peers.size)
    assert(response.nodes.size == 8)

  }

  test("announcePeer") {
    val response = sendToServer(
      c =>
        c.getPeers(serverNode, infoHash)
          .map(_.info.token)
          .flatMap { token =>
            c.announcePeer(serverNode, token, infoHash, Port(1222).get)
          }
          .flatMap { _ =>
            c.getPeers(serverNode, infoHash)
          }
    ).unsafeRunSync()

    assert(response.peers.size == peers.size + 1)
  }

  test("announcePeer with bad token") {
    val response = sendToServer(
      c =>
        c.getPeers(serverNode, infoHash).map(_.info.token).flatMap { token =>
          c.announcePeer(serverNode, token, infoHash, Port(1222).get)
            .delayBy(210.millis)
        }
    ).attempt.unsafeRunSync().leftMap {
      case e: Error => e.show.contains("Invalid token")
      case _        => false
    }

    assertEquals(response, true.asLeft)

  }
}

object ServerSpec {
  final case class PingClient() extends Client.Ping {
    override def ping(node: Node): Stream[IO, KMessage.NodeIdResponse] = ???
  }
  object PingClient {}
}
