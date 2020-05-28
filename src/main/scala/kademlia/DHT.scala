package kademlia

import java.time.Clock

import cats.Apply
import cats.effect.concurrent.Ref
import cats.effect.{ Concurrent, ContextShift, IO, Timer }
import kademlia.protocol.KMessage.{
  GetPeersNodesResponse,
  GetPeersResponse,
  ImpliedPort
}
import kademlia.protocol.{ InfoHash, Peer, Token }
import kademlia.types.{ Contact, Distance, Node, NodeId, NodeInfo }
import cats.implicits._
import com.comcast.ip4s._
import fs2.Stream
import fs2.io.udp.SocketGroup
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

import Function._
import scala.concurrent.duration._

trait DHT {
  def lookup(infoHash: InfoHash): IO[List[Peer]]
  def announce(infoHash: InfoHash, port: Port): IO[Unit]
  def start(): Stream[IO, Unit]
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
      port: Port,
      nodes: IO[List[Node]] = BootstrapNodes()
  )(
      implicit c: Concurrent[IO],
      cs: ContextShift[IO],
      time: Timer[IO],
      clock: Clock
  ): IO[DHT] = {
    val client = Client(nodeId, sg)
    val server = Server(nodeId, sg, port)
    for {
      table  <- IO.fromEither(Table.empty(nodeId))
      ref    <- Ref.of(table)
      cache  <- MemCache.empty[String, List[NodeInfo]](10.minutes)
      _nodes <- nodes
      dht    <- DHTDef(nodeId, ref, cache, client, server).bootstrap(_nodes)
    } yield dht
  }

}

final case class DHTDef(
    nodeId: NodeId,
    table: Ref[IO, Table],
    cache: MemCache[String, List[NodeInfo]],
    client: Client,
    server: Server
)(
    implicit c: Concurrent[IO],
    cs: ContextShift[IO],
    timer: Timer[IO],
    clock: Clock
) extends DHT {

  import DHT.logger

  override def lookup(infoHash: InfoHash): IO[List[Peer]] = {
    for {
      (peers, _) <- _lookup(infoHash)
    } yield peers

  }
  def _lookup(infoHash: InfoHash): IO[(List[Peer], List[NodeInfo])] = {
    for {
      t <- table.get
      nodes = t.neighbors(NodeId(infoHash.value))
      (peers, infos) <- findPeers(nodes, infoHash)
      _              <- saveNodeInfo(infoHash, infos)
    } yield (peers, infos)

  }
  override def announce(infoHash: InfoHash, port: Port): IO[Unit] = {
    for {
      nodesInfo <- cache.get(infoHash.value.toHex)
      nodes     <- nodesInfo.fold(_lookup(infoHash).map(_._2))(IO(_))
      _ <- Stream
        .emits(nodes)
        .map { n =>
          client
            .announcePeer(
              n.node.contact,
              ImpliedPort(true),
              infoHash,
              port,
              n.token
            )
            .handleErrorWith {
              case e: Error =>
                Stream.eval_(
                  logger.debug(s"Announce peer error. node: $n ${e.show}")
                ) ++
                  markNodeAsBad(n.node)
              case e: Throwable =>
                Stream.eval_(
                  logger.debug(s"Announce peer error. node: $n ${e.getMessage}")
                ) ++
                  markNodeAsBad(n.node)
            }
        }
        .parJoin(8)
        .compile
        .toList
    } yield ()
  }

  override def start(): Stream[IO, Unit] = {
    server.start()
  }

  def bootstrap(nodes: List[Node]): IO[DHTDef] = {
    for {
      _nodes <- findNodesFrom(nodes)
      t      <- table.get
      t2     <- IO.fromEither(t.addNodes(_nodes))
      newRef <- Ref.of(t2)
    } yield DHTDef(nodeId, newRef, cache, client, server)
  }

  def updateTable(f: Table => Result[Table]): IO[Table] =
    for {
      t  <- table.get
      nt <- IO.fromEither(f(t))
      _  <- table.set(nt)
    } yield nt

  def addNodes(n: List[Node]): IO[Table] = updateTable(_.addNodes(n))
  def markNodeAsBad(n: Node): Stream[IO, Nothing] =
    Stream.eval_(updateTable(_.markNodeAsBad(n)))

  final case class NodeResponse(
      info: NodeInfo,
      nodes: List[Node],
      peers: List[Peer]
  )

  def saveNodeInfo(infoHash: InfoHash, info: List[NodeInfo]): IO[Unit] = {
    cache.put(infoHash.value.toHex, info)
  }

  def findPeers(
      nodes: List[Node],
      infoHash: InfoHash
  ): IO[(List[Peer], List[NodeInfo])] = {

    def go(
        _nodes: List[Node],
        previous: List[NodeResponse],
        peers: List[Peer]
    ): IO[(List[Peer], List[NodeInfo])] = {

      def find: IO[List[NodeResponse]] =
        Stream
          .emits(_nodes)
          .map { n =>
            client
              .getPeers(n.contact, infoHash)
              .evalMap {
                case Left(GetPeersNodesResponse(_, _, token, nn)) =>
                  IO(
                    NodeResponse(
                      NodeInfo(token, n, nodeId.distance(n.nodeId)),
                      nn,
                      Nil
                    )
                  )
                case Right(GetPeersResponse(_, _, token, values)) =>
                  IO(
                    NodeResponse(
                      NodeInfo(token, n, nodeId.distance(n.nodeId)),
                      Nil,
                      values
                    )
                  )
              }
              .handleErrorWith {
                case e: Error =>
                  Stream.eval_(
                    logger.debug(s"Get peers error. node: $n ${e.show}")
                  ) ++
                    markNodeAsBad(n)
                case e: Throwable =>
                  Stream.eval_(
                    logger.debug(s"Get peers error. node: $n ${e.getMessage}")
                  ) ++
                    markNodeAsBad(n)
              }
          }
          .parJoin(8)
          .compile
          .toList

      for {
        newResponse <- find
        newPeers = newResponse.flatMap(_.peers.toSet.toList)
        newNodes = newResponse.map(_.info.node)
        _ <- addNodes(newNodes.toSet.toList)
        sorted = newResponse.sortBy(_.info.distance).take(8)
        (p, _) <- previous match {
          case Nil => go(sorted.flatMap(_.nodes), sorted, newPeers ++ peers)
          case xs =>
            xs.zip(sorted)
              .map { case (a, b) => a.info.distance > b.info.distance }
              .find(_ == true)
              .fold(IO((newPeers ++ peers) -> sorted.map(_.info)))(
                _ => go(sorted.flatMap(_.nodes), sorted, newPeers ++ peers)
              )
        }

        infos = sorted.map(_.info) ++ previous.map(_.info)
      } yield (p, infos)

    }

    go(nodes, Nil, Nil)
  }

  def findNodesFrom(nodes: List[Node], count: Int = 3): IO[List[Node]] = {
    def find =
      Stream
        .emits(nodes)
        .take(8)
        .map { n =>
          client
            .findNode(n.contact, nodeId)
            .map(_.nodes)
            .handleErrorWith {
              case e: Error =>
                Stream.eval_(
                  logger.debug(s"Find node error. node: $n ${e.show}")
                ) ++
                  markNodeAsBad(n)
              case e: Throwable =>
                Stream.eval_(
                  logger.debug(s"Find node error. node: $n ${e.getMessage}")
                ) ++
                  markNodeAsBad(n)
            }

        }
        .parJoin(8)
        .flatMap(Stream.emits(_))
        .compile
        .toList

    for {
      found <- find
      n = (nodes ++ found)
        .map(v => v -> v.nodeId.distance(nodeId))
        .sortBy { case (_, distance) => distance }
        .map(_._1)
      res <- if (count == 0) IO(n)
      else findNodesFrom(n, count - 1)
    } yield res
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
