package kademlia

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
  def table: IO[Table]
  //def node(t:String, port:Int)
  //def get(key)
  //def put(key)
  //def stop()
}

object DHT {
  val logger = Slf4jLogger.getLogger[IO]

  def bootstrap(
      sg: SocketGroup,
      nodeId: NodeId = NodeId.gen(),
      nodes: IO[List[Node]] = BootstrapNodes()
  )(
      implicit c: Concurrent[IO],
      cs: ContextShift[IO],
      clock: Clock
  ): DHT = new DHT {
    val client = Client(nodeId, sg)
    override lazy val table: IO[Table] = {
      for {
        table  <- IO.fromEither(Table.empty(nodeId))
        _      <- logger.debug(s"empty table is created $table")
        n      <- findNodesFrom(nodes)
        _      <- logger.debug(s"result size: ${n.size}")
        result <- IO.fromEither(table.addNodes(n.toSet.toList))
      } yield result
    }

    def findNodesFrom(nodes: IO[List[Node]], count: Int = 3): IO[List[Node]] = {
      def find =
        Stream
          .evals(nodes)
          .take(8)
          .map { n =>
            client
              .findNode(n.contact, nodeId)
              .map(_.nodes)

          }
          .parJoin(8)
          .flatMap(Stream.emits(_))
          .compile
          .toList

      for {
        orig  <- nodes
        found <- find
        n = nodeId.sortByDistance(orig ++ found)
        res <- if (count == 0) IO(n)
        else findNodesFrom(IO(n), count - 1)
      } yield res
    }

  }

}

final case class BootstrapNodes(contacts: (Hostname, Port)*) {
  def bootstrapNode(
      hostname: Hostname,
      port: Port = Port(6881).get
  ): IO[Node] = {
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
  def nodes: IO[List[Node]] = contacts.toList.traverse {
    case (host, port) =>
      bootstrapNode(host, port)
  }
}

object BootstrapNodes {
  val port           = Port(6881).get
  val transmissionbt = host"dht.transmissionbt.com" -> port
  val bittorrent     = host"router.bittorrent.com" -> port
  val utorrent       = host"router.utorrent.com" -> port
  val silotis        = host"router.silotis.us" -> port

  def apply(): IO[List[Node]] =
    BootstrapNodes(transmissionbt, bittorrent, utorrent, silotis).nodes
}
