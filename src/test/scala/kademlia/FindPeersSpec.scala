package kademlia

import cats.effect.IO
import cats.effect.concurrent.Ref
import fs2.Stream
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import kademlia.protocol.{ InfoHash, Peer }
import kademlia.types.{ Node, NodeInfo }
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import scala.concurrent.duration._

class FindPeersSpec extends KSuite {

  import FindPeersSpec._
  test("findPeers") {

    forAll(infoHashGen, Gen.nonEmptyListOf(nodeGen()), nodeIdIntGen) {
      (infohash, nodes, nodeId) =>
        val (peers, tableState, nodesForAnnounce, expected) = (for {
          ts               <- TableState.empty(nodeId)
          cache            <- NodeInfoCache.create(1.minute)
          client           <- GetPeersClient()
          peers            <- FindPeers(nodes.take(3), infohash, client, ts, cache).compile.toList
          expected         <- client.getExpectedPeers
          nodesForAnnounce <- cache.get(infohash)
        } yield (peers, ts, nodesForAnnounce, expected)).unsafeRunSync()

        println(expected.size)
        nodesForAnnounce.nonEmpty && peers.size == expected.size
    }
  }

}

object FindPeersSpec extends KGens {
  val _logger = Slf4jLogger.getLogger[IO]
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
