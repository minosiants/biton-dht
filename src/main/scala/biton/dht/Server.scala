package biton.dht

import java.net.InetSocketAddress

import biton.dht.protocol.KMessage._
import biton.dht.protocol.{ InfoHash, KMessage, KMessageSocket, Peer, Token }
import biton.dht.types.{ Node, NodeId }
import cats.effect.concurrent.Ref
import cats.effect.{ Concurrent, ContextShift, IO }
import cats.syntax.apply._
import cats.syntax.option._
import cats.syntax.functor._
import com.comcast.ip4s.{ IpAddress, Port }
import fs2.Stream
import fs2.io.udp.SocketGroup
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

trait Server {
  def start(): Stream[IO, Unit]
}

object Server {
  val logger = Slf4jLogger.getLogger[IO]

  def apply(
      id: NodeId,
      table: TableState,
      store: PeerStore,
      sg: SocketGroup,
      port: Port
  )(
      implicit c: Concurrent[IO],
      cs: ContextShift[IO]
  ): Server = new Server() {

    def addNode(nodeId: NodeId, remote: InetSocketAddress): IO[Unit] = {
      remote.toContact
        .map(Node(nodeId, _))
        .fold(_ => IO(()), table.addNode(_).void)
    }
    def validateToken(token: Token): IO[Token] = {
      ???
    }
    override def start(): Stream[IO, Unit] = {

      Stream.eval_(logger.info(s"Starting server on port $port")) ++
        KMessageSocket
          .createSocket(sg, None, port.some)
          .flatMap { s =>
            s.read.evalMap {
              case (remote, KMessage.Ping(t, senderId)) =>
                logger.debug("ping") *>
                  addNode(senderId, remote) *>
                  s.write1(remote, NodeIdResponse(t, id))

              case (remote, KMessage.FindNode(t, senderId, target)) =>
                for {
                  _         <- logger.debug("FindNode")
                  neighbors <- table.neighborsOf(target)
                  _         <- addNode(senderId, remote)
                  _         <- s.write1(remote, FindNodeResponse(t, id, neighbors))
                } yield ()

              case (remote, KMessage.GetPeers(t, senderId, infohash)) =>
                for {
                  _     <- logger.debug("GetPeers")
                  peers <- store.get(infohash)
                  nodes <- table.neighbors(infohash)
                  _ <- s.write1(
                    remote,
                    NodesWithPeersResponse(
                      t,
                      id,
                      Token.gen(),
                      nodes.some,
                      peers
                    )
                  )
                } yield ()

              case (
                  remote,
                  KMessage.AnnouncePeer(
                    t,
                    impliedPort,
                    senderId,
                    infoHash,
                    port,
                    token
                  )
                  ) =>
                for {
                  _ <- logger.debug("AnnouncePeer")
                  _ <- validateToken(token)
                  _ <- addNode(senderId, remote)
                  peer <- IO.fromEither(
                    impliedPort.implied(remote.toPeer(port), remote.toPeer())
                  )
                  _ <- store.add(infoHash, peer)
                  _ <- s.write1(remote, NodeIdResponse(t, id))
                } yield ()

              case _ =>
                logger.debug("unsupported")
            }
          }
    }
  }
}

trait PeerStore {
  def add(infoHash: InfoHash, peer: Peer): IO[Unit]
  def get(infoHash: InfoHash): IO[Option[List[Peer]]]
}

object PeerStore {
  def inmemory(): IO[PeerStore] =
    Ref[IO].of(Map.empty[String, Set[Peer]]).map { ref =>
      new PeerStore {
        override def add(infoHash: InfoHash, peer: Peer): IO[Unit] =
          ref
            .modify { m =>
              val key    = infoHash.toHex
              val v      = m.getOrElse(key, Set.empty[Peer]) + peer
              val result = m + (key -> v)
              result -> (result, ())
            }
            .as(())

        override def get(infoHash: InfoHash): IO[Option[List[Peer]]] =
          ref.get.map(_.get(infoHash.toHex).map(_.toList))
      }
    }
}
