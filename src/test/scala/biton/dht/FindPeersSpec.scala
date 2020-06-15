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

import biton.dht.Conf.{ CacheExpiration, GoodDuration }
import biton.dht.protocol._
import biton.dht.types.{ Node, NodeInfo }
import cats.effect.IO
import cats.effect.concurrent.Ref
import fs2.Stream
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import scala.concurrent.duration._

class FindPeersSpec extends KSuite {

  import FindPeersSpec._
  test("findPeers") {

    forAll(infoHashGen, Gen.nonEmptyListOf(nodeGen()), nodeIdIntGen) {
      (infohash, nodes, nodeId) =>
        val (peers, nodesForAnnounce, expected) = (for {
          ts               <- TableState.empty(nodeId, PingClient(), GoodDuration(1.minute))
          cache            <- NodeInfoCache.create(CacheExpiration(1.minute))
          client           <- GetPeersClient()
          peers            <- FindPeers(nodes.take(3), infohash, client, ts, cache).compile.toList
          expected         <- client.getExpectedPeers
          nodesForAnnounce <- cache.get(infohash)
        } yield (peers, nodesForAnnounce, expected)).unsafeRunSync()

        nodesForAnnounce.nonEmpty && peers.size == expected.size
    }
  }

}

object FindPeersSpec extends KGens {

  case class PingClient() extends Client.Ping {
    override def ping(node: Node): Stream[IO, KMessage.NodeIdResponse] = {
      Stream.emit(
        KMessage.NodeIdResponse(Random[Transaction].value, node.nodeId)
      )
    }
  }

  case class GetPeersClient(
      peersRef: Ref[IO, List[Peer]],
      counter: Ref[IO, Int]
  ) extends Client.GetPeers {
    def getExpectedPeers: IO[List[Peer]] = peersRef.get
    def getCount: IO[Int]                = counter.get

    override def getPeers(
        node: Node,
        infoHash: InfoHash
    ): Stream[IO, NodeResponse] = {

      Stream.eval(getCount).flatMap { v =>
        if (v != 0 && v % 4 == 0)
          Stream.eval_(counter.modify(v => v + 1 -> (v + 1))) ++
            Stream
              .eval(IO.raiseError(Error.ServerError("Error !!!!!!!!!!!!!!!")))
        else {
          val res = nodeResponseGen(node).sample.get
          Stream.eval_(counter.modify(v => v + 1            -> (v + 1))) ++
            Stream.eval(peersRef.modify(v => v ++ res.peers -> res))
        }
      }
    }
  }

  object GetPeersClient {
    def apply(): IO[GetPeersClient] =
      for {
        peerRef    <- Ref[IO].of(List.empty[Peer])
        counterRef <- Ref[IO].of(0)
      } yield GetPeersClient(peerRef, counterRef)

  }
  def nodeResponseGen(node: Node): Gen[NodeResponse] =
    for {
      token <- tokenGen
      peers <- Gen.listOfN(3, peerGen)
      nodes <- Gen.listOfN(3, nodeGen())
    } yield NodeResponse(NodeInfo(token, node), nodes, peers)

}
