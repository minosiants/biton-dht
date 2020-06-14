package biton.dht

import java.nio.file.Path
import java.time.Clock

import biton.dht.Conf.{
  CacheExpiration,
  RefreshTableDelay,
  SaveTableDelay
}
import biton.dht.Error.FileOpsError
import biton.dht.FindNodes.FindNodesStream
import biton.dht.protocol._
import biton.dht.types._
import cats.effect.{ Concurrent, ContextShift, IO, Timer }
import cats.implicits._
import com.comcast.ip4s._
import fs2.io.udp.SocketGroup
import fs2.{ Pipe, Stream }
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

trait DHT {
  def lookup(infoHash: InfoHash): Stream[IO, Peer]
  def announce(infoHash: InfoHash, port: Port): IO[Unit]
  //for BitTorrent Protocol Extension BEP5
  def addNode(node: Node): IO[Unit]
  def table: IO[Table]
  def start(): Stream[IO, Unit]
  //def node(t:String, port:Int)
  //def get(key)
  //def put(key)
  //def stop()
}

object DHT {
  val logger = Slf4jLogger.getLogger[IO]

  def apply(sg: SocketGroup, conf: Conf)(
      implicit c: Concurrent[IO],
      cs: ContextShift[IO],
      time: Timer[IO],
      clock: Clock
  ): IO[DHT] = {

    fromTable(sg, conf).recoverWith {
      case FileOpsError(_, _) => bootstrap(sg, conf)
    }
  }
  def fromTable(
      sg: SocketGroup,
      conf: Conf
  )(
      implicit c: Concurrent[IO],
      cs: ContextShift[IO],
      time: Timer[IO],
      clock: Clock
  ): IO[DHT] = {

    val client = Client(conf.nodeId, sg)

    for {
      t <- TableSerialization.fromFile(conf.saveTableDir, conf.nodeId)
      table = Table.nonEmpty(t, client, conf.goodDuration)
      tableState <- TableState.create(table)
      cache      <- NodeInfoCache.create(conf.cacheExpiration)
      secrets    <- Secrets.create(conf.secretExpiration)
      store      <- PeerStore.inmemory()
      server = Server(table.nodeId, tableState, store, secrets, sg, conf.port)
    } yield DHTDef(
      table.nodeId,
      tableState,
      conf.saveTableDir,
      conf.refreshTableDelay,
      conf.saveTableDelay,
      cache,
      client,
      server
    )
  }

  def bootstrap(
      sg: SocketGroup,
      conf: Conf,
      contacts: IO[List[Contact]] = BootstrapContacts()
  )(
      implicit c: Concurrent[IO],
      cs: ContextShift[IO],
      time: Timer[IO],
      clock: Clock
  ): IO[DHT] = {
    val client = Client(conf.nodeId, sg)

    for {
      tableState <- TableState.empty(conf.nodeId, client, conf.goodDuration)
      cache      <- NodeInfoCache.create(conf.cacheExpiration)
      secrets    <- Secrets.create(conf.secretExpiration)
      store      <- PeerStore.inmemory()
      server = Server(conf.nodeId, tableState, store, secrets, sg, conf.port)
      _contacts <- contacts
      dht <- DHTDef(
        conf.nodeId,
        tableState,
        conf.saveTableDir,
        conf.refreshTableDelay,
        conf.saveTableDelay,
        cache,
        client,
        server
      ).bootstrap(_contacts)
    } yield dht
  }

}

final case class DHTDef(
    nodeId: NodeId,
    tableState: TableState,
    saveTableDir: Path,
    refreshTableDelay: RefreshTableDelay,
    saveTableDelay: SaveTableDelay,
    cache: NodeInfoCache,
    client: Client,
    server: Server
)(
    implicit c: Concurrent[IO],
    cs: ContextShift[IO],
    timer: Timer[IO],
    clock: Clock
) extends DHT {

  override def table: IO[Table] = tableState.get

  override def lookup(infoHash: InfoHash): Stream[IO, Peer] =
    Stream
      .eval(tableState.neighbors(infoHash.toNodeId))
      .flatMap(
        FindPeers(_, infoHash, client, tableState, cache)
      )

  override def announce(infoHash: InfoHash, port: Port): IO[Unit] = {

    Stream
      .eval(cache.get(infoHash))
      .flatMap {
        case None =>
          lookup(infoHash).onComplete {
            Stream
              .eval(cache.get(infoHash))
              .flatMap(v => Stream.emits(v.getOrElse(List.empty)))
          }
        case Some(value) => Stream.emits(value)
      }
      .map {
        case NodeInfo(token, node) =>
          client
            .announcePeer(node, token, infoHash, port)
      }
      .parJoin(8)
      .compile
      .drain

  }

  override def start(): Stream[IO, Unit] = {
    server
      .start()
      .concurrently(refreshTable)
      .concurrently(cache.purgeExpired)
      .concurrently(saveTable)
  }

  def bootstrap(contacts: List[Contact]): IO[DHTDef] = {
    for {
      nodes    <- findFromContact(contacts)
      _nodes   <- findNodesFrom(nodes, 3)
      t2       <- tableState.addNodes(_nodes)
      newState <- TableState.create(t2)
    } yield this.copy(tableState = newState)
  }

  def findFromContact(contacts: List[Contact]): IO[List[Node]] = {
    Stream
      .emits(contacts)
      .map(client.findNode(_, nodeId).mask)
      .parJoin(contacts.size)
      .flatMap(Stream.emits(_))
      .compile
      .toList

  }
  def findNodesFrom(nodes: List[Node], count: Int): IO[List[Node]] = {
    def find =
      Stream
        .emits(nodes)
        .take(8)
        .map { n =>
          client
            .findNode(n.contact, nodeId)
            .handleErrorWith(_ => tableState.markNodeAsBad(n))
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

  override def addNode(node: Node): IO[Unit] = {
    tableState.addNode(node).void
  }

  def refreshTable: Stream[IO, Unit] =
    Stream
      .eval(tableState.get.map(_.outdated.map(_.random)))
      .flatMap(Stream.emits(_))
      .flatMap { target =>
        Stream
          .eval(tableState.neighbors(target.nodeId))
          .flatMap { nodes =>
            FindNodes(target.nodeId, nodes, client, tableState)
          }
          .evalMap {
            case (stale, good) =>
              tableState.markNodesAsBad(stale) *>
                tableState.addNodes(good)
          }
          .void
      }
      .delayBy(refreshTableDelay.value)
      .repeat

  def saveTable: Stream[IO, Unit] =
    Stream
      .eval_(for {
        t <- tableState.get
        _ <- TableSerialization.toFile(saveTableDir, t)
      } yield ())
      .delayBy(saveTableDelay.value)
      .repeat

}

final case class BootstrapContacts(contactInfo: (Hostname, Port)*) {
  def bootstrapContact(
      hostname: Hostname,
      port: Port = Port(6881).get
  ): IO[Contact] = {
    hostname.resolve
      .map(
        _.map(
          ip => Contact(ip, port)
        )
      )
      .flatMap(
        v =>
          IO.fromEither(
            v.toRight(Error.DHTError(s"$hostname can not be resolved"))
          )
      )
  }
  def contacts: IO[List[Contact]] = contactInfo.toList.traverse {
    case (host, port) =>
      bootstrapContact(host, port)
  }
}

object BootstrapContacts {
  val port           = Port(6881).get
  val transmissionbt = host"dht.transmissionbt.com" -> port
  val bittorrent     = host"router.bittorrent.com" -> port
  val utorrent       = host"router.utorrent.com" -> port
  val silotis        = host"router.silotis.us" -> port

  def apply(): IO[List[Contact]] =
    BootstrapContacts(transmissionbt, bittorrent, utorrent, silotis).contacts
}

trait NodeInfoCache {
  def put(infoHash: InfoHash, info: List[NodeInfo]): IO[Unit]
  def get(infoHash: InfoHash): IO[Option[List[NodeInfo]]]
  def purgeExpired: Stream[IO, Unit]
}

object NodeInfoCache {
  def create(
      expires: CacheExpiration
  )(implicit timer: Timer[IO], clock: Clock): IO[NodeInfoCache] =
    MemCache.empty[String, List[NodeInfo]](expires).map { cache =>
      new NodeInfoCache {
        override def put(infoHash: InfoHash, info: List[NodeInfo]): IO[Unit] =
          cache.put(infoHash.value.toHex, info)

        override def get(infoHash: InfoHash): IO[Option[List[NodeInfo]]] =
          cache.get(infoHash.value.toHex)

        override def purgeExpired: Stream[IO, Unit] =
          Stream.eval_(cache.purgeExpired).delayBy(expires.value).repeat
      }

    }
}

final case class FindNodes(
    target: NodeId,
    nodes: List[Node],
    client: Client.FindNode,
    tableState: TableState,
    logger: Logger[IO]
)(implicit c: Concurrent[IO]) {

  def requestNodes: Pipe[IO, List[Node], FindNodes.Response] =
    _.flatMap {
      Stream
        .emits(_)
        .map(
          node =>
            client
              .findNode(node.contact, target)
              .attempt
              .map {
                case Left(_) =>
                  List(node) -> Nil
                case Right(v) => Nil -> List(FindNodes.NodeResponse(node, v))
              }
        )
        .parJoin(3)
        .foldMonoid
        .map {
          case (stale, responded) => (stale.distinct, responded.distinct)
        }
    }

  def processResult(
      tt: TraversalTable[Node],
      go: TraversalTable[Node] => FindNodesStream
  ): Pipe[IO, FindNodes.Response, (List[Node], List[Node])] =
    _.flatMap {
      case (stale, responded) =>
        tt.markNodesAsStale(stale)
          .markNodesAsResponded(responded.map(_.node))(identity) match {
          case TraversalTable.Completed(_, _, _) =>
            //Stream.eval_(logger.error(s"!!! Completed")).drain ++
            Stream.emit(stale -> responded.flatMap(_.nodes))
          case t @ TraversalTable.InProgress(_, _, _) =>
            // Stream.eval_(logger.error(s"!!! InProgress")).drain ++
            // Stream.eval_(logger.error(TraversalTable.log(nodes))).drain ++
            Stream.emit(stale -> responded.flatMap(_.nodes)) ++
              go(t.addNodes(responded.flatMap(_.nodes)))
        }
    }

  def findNodes: FindNodesStream = {
    def go(tt: TraversalTable[Node]): FindNodesStream =
      Stream
        .emit(tt.topFresh(3))
        .through(requestNodes)
        .through(processResult(tt, go))

    go(TraversalTable.create(target, nodes, 8))
  }
}

object FindNodes {
  val logger = Slf4jLogger.getLogger[IO]
  final case class NodeResponse(node: Node, nodes: List[Node])
  type Response        = (List[Node], List[NodeResponse])
  type StaleNodes      = List[Node]
  type GoodNodes       = List[Node]
  type FindNodesStream = Stream[IO, (StaleNodes, GoodNodes)]
  def apply(
      target: NodeId,
      nodes: List[Node],
      client: Client.FindNode,
      tableState: TableState
  )(implicit c: Concurrent[IO]): FindNodesStream =
    FindNodes(target, nodes, client, tableState, logger).findNodes
}

final case class FindPeers(
    nodes: List[Node],
    infoHash: InfoHash,
    client: Client.GetPeers,
    tableState: TableState,
    cache: NodeInfoCache,
    logger: Logger[IO]
)(implicit c: Concurrent[IO]) {
  import FindPeers.Response

  val sep = Stream.eval_(logger.debug(List.fill(20)("_").mkString(""))).drain

  val requestPeers: Pipe[IO, List[Node], Response] =
    _.flatMap {
      Stream
        .emits(_)
        .map(
          node =>
            client
              .getPeers(node, infoHash)
              .attempt
              .map {
                case Left(_) =>
                  List(node) -> Nil
                case Right(v) => Nil -> List(v)
              }
        )
        .parJoin(3)
        .foldMonoid
        .map {
          case (stale, responded) => (stale.distinct, responded.distinct)
        }
    }
  val logResult: Pipe[IO, Response, Response] =
    _.evalTap {
      case (stale, responded) =>
        logger.error(s"stale $stale") *>
          logger.error(s"responded $responded") *>
          logger.error(s"peers ${responded.flatMap(_.peers).size}")
    }

  def processResult(
      tt: TraversalTable[NodeInfo],
      go: TraversalTable[NodeInfo] => Stream[IO, Peer]
  ): Pipe[IO, Response, Peer] =
    _.flatMap {
      case (stale, responded) =>
        Stream.eval_(tableState.markNodesAsBad(stale)) ++ {
          tt.markNodesAsStale(stale)
            .markNodesAsResponded(responded.map(_.info))(_.node) match {
            case t @ TraversalTable.Completed(_, _, _) =>
              //Stream.eval_(logger.error(s"!!! Completed")).drain ++
              Stream
                .eval_(cache.put(infoHash, t.topResponded(8))) ++
                Stream.emits(responded.flatMap(_.peers))
            case t @ TraversalTable.InProgress(_, _, _) =>
              // Stream.eval_(logger.error(s"!!! InProgress")).drain ++
              // Stream.eval_(logger.error(TraversalTable.log(nodes))).drain ++
              Stream.emits(responded.flatMap(_.peers)) ++
                go(t.addNodes(responded.flatMap(_.nodes)))
          }
        }
    }

  def findPeers: Stream[IO, Peer] = {

    def go(tt: TraversalTable[NodeInfo]): Stream[IO, Peer] =
      Stream
        .emit(tt.topFresh(3))
        .through(requestPeers)
        //    .through(logResult)
        .through(processResult(tt, go))

    go(TraversalTable.create(infoHash.toNodeId, nodes, 8))
  }
}

object FindPeers {

  val logger = Slf4jLogger.getLogger[IO]
  type Response = (List[Node], List[NodeResponse])

  def apply(
      nodes: List[Node],
      infoHash: InfoHash,
      client: Client.GetPeers,
      tableState: TableState,
      cache: NodeInfoCache
  )(implicit c: Concurrent[IO]): Stream[IO, Peer] =
    FindPeers(nodes, infoHash, client, tableState, cache, logger).findPeers
}
