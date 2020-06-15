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

import scala.concurrent.duration._

import com.comcast.ip4s.{ IpAddress, Port }

import cats.effect.{ Blocker, IO }
import cats.instances.list._
import cats.syntax.either._
import cats.syntax.show._
import cats.syntax.traverse._

import fs2._
import fs2.io.udp.SocketGroup

import scodec.bits.BitVector

import org.scalacheck.Gen

import biton.dht.Conf.{ GoodDuration, SecretExpiration }
import biton.dht.protocol.KMessage.NodeIdResponse
import biton.dht.protocol.{ KMessage, Transaction }
import biton.dht.types._

class ServerSpec extends KSuite {

  def serverNode(port: Int) =
    (for {
      ip   <- IpAddress("127.0.0.1")
      port <- Port(port)
    } yield Node(NodeId.fromBigInt(2), Contact(ip, port))).get

  def kbucket(to: Int = 30): KBucket =
    kbucketGen(Prefix(0), Prefix(BigInt(to)), KSize(10), 10).sample.get

  def tableState(node: Node, pingClient: Client.Ping): IO[TableState] = {
    val nodes = kbucket().nodes.filterNot(node).value.toList.map(_.node)
    for {
      ts <- TableState.empty(
        node.nodeId,
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
  def sendToServer[A](node: Node)(f: Client => Stream[IO, A]): IO[A] =
    Blocker[IO]
      .use { blocker =>
        SocketGroup[IO](blocker).use { sg =>
          val client = Client(clientNodeId, sg)
          for {
            secrets <- Secrets.create(SecretExpiration(1.second))
            table   <- tableState(node, client)
            store   <- PeerStore.inmemory()
            _       <- peers.traverse(v => store.add(infoHash, v))
            server = Server(
              node.nodeId,
              table,
              store,
              secrets,
              sg,
              node.contact.port
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
    val node   = serverNode(2229)
    val result = sendToServer(node)(_.ping(node)).unsafeRunSync()
    assertEquals(result, NodeIdResponse(transaction, node.nodeId))

  }

  test("findNode") {
    val node = serverNode(2223)
    val response = sendToServer(node)(
      _.findNode(node.contact, node.nodeId)
    ).unsafeRunSync()

    assert(response.size == 8)
    assert(ordered(response, node.nodeId))
  }

  test("getPeers") {
    val node = serverNode(2224)
    val response = sendToServer(node)(
      _.getPeers(node, infoHash)
    ).unsafeRunSync()
    assert(response.peers.size == peers.size)
    assert(response.nodes.size == 8)

  }

  test("announcePeer") {
    val node = serverNode(2225)
    val response = sendToServer(node)(
      c =>
        c.getPeers(node, infoHash)
          .map(_.info.token)
          .flatMap { token =>
            c.announcePeer(node, token, infoHash, Port(1222).get)
          }
          .flatMap { _ =>
            c.getPeers(node, infoHash)
          }
    ).attempt.unsafeRunSync()

    response.leftMap{
      case e:Error => println(e.show)
    }
    assert(response.map(_.peers.size) == (peers.size + 1).asRight)
  }

  test("announcePeer with bad token") {
    val node = serverNode(2226)
    val response = sendToServer(node)(
      c =>
        c.getPeers(node, infoHash).map(_.info.token).flatMap { token =>
          c.announcePeer(node, token, infoHash, Port(1222).get)
            .delayBy(2.second)
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
