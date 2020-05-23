package kademlia

import java.nio.channels.InterruptedByTimeoutException
import java.time.Clock

import cats.effect.{ Concurrent, ContextShift, IO }
/*import cats.syntax.order._
import cats.syntax.eq._
import cats.instances.list._*/
import cats.implicits._
import com.comcast.ip4s._
import fs2.Stream
import fs2.io.udp.SocketGroup
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import kademlia.types.{ Contact, Node, NodeId }

trait DHT {
  //
  //def node(t:String, port:Int)
  def bootstrap()
  //def get(key)
  //def put(key)
  //def stop()
}

object DHT {
  val logger = Slf4jLogger.getLogger[IO]
  def bootstrapNode(
      hostname: Hostname,
      port: Port = Port(6881).get
  ): IO[Node] = {
    println(hostname.toString.getBytes().size)
    hostname.resolve
      .map(
        _.map(
          ip => Node(NodeId.fromString(hostname.toString), Contact(ip, port))
        )
      )
      .flatMap(
        v =>
          IO.fromEither(
            v.toRight(Error.DHTError(s"$hostname can not be resolved"))
          )
      )
  }

  val transmissionbt = bootstrapNode(host"dht.transmissionbt.com")
  val bittorrent     = bootstrapNode(host"router.bittorrent.com")
  val utorrent       = bootstrapNode(host"router.utorrent.com")
  val silotis        = bootstrapNode(host"router.silotis.us")

  val nodeId = NodeId.gen()

  def bootstrap(
      sg: SocketGroup
  )(
      implicit c: Concurrent[IO],
      cs: ContextShift[IO],
      clock: Clock
  ): IO[Table] = {

    def sortNodes(nodeId: NodeId, nodes: List[Node]): List[Node] = {
      nodes.sortWith { (a, b) =>
        val f = nodeId ^ a.nodeId
        val s = nodeId ^ b.nodeId
        f > s
      }
    }
    def go(table: Table, nodes: List[Node]): IO[Table] = {
      lazy val newNodes =
        Stream
          .emits(nodes)
          .take(8)
          .map { n =>
            Client(nodeId, n.contact, sg)
              .findNode(table.nodeId)
              .map(_.nodes)
          }
          .parJoin(3)
          .flatMap(Stream.emits(_))
          .compile
          .toList

      def saveNodes(n: List[Node]) = {
        val sorted   = sortNodes(table.nodeId, n.toSet.toList)
        val continue = table.nonFull && nodes.nonEmpty && sorted =!= nodes
        for {
          _ <- logger.debug(s"sorted: $sorted hasChanges: $continue")
          updated <- if (continue) IO.fromEither(table.addNodes(n))
          else IO(table)
          _ <- logger.debug(s"nodes: ${nodes.size}  ${nodes}")
          _ <- logger.debug(
            s"table size: ${updated.size} bsize: ${updated.bsize}"
          )
          rr <- if (continue) go(updated, sorted) else IO(table)
        } yield rr
      }
      for {
        _ <- logger.debug(s"nodes size: ${nodes.size}")
        n <- newNodes
        _ <- logger.debug(s"go result: $n")
        t <- if (n.isEmpty && nodes.drop(8).nonEmpty) go(table, nodes.drop(8))
        else saveNodes(n)
      } yield t

    }
    for {
      table <- Table.empty(nodeId)
      _     <- logger.debug(s"empty table is created $table")
      // contact <- transmissionbt
      bnode <- bittorrent
      // bnode <- silotis
      _      <- logger.debug(s"contact is created $bnode")
      result <- go(table, List(bnode))
      _      <- logger.debug(s"result size: ${result.size}")
    } yield result

  }
}
