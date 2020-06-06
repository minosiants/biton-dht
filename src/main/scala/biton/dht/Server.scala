package biton.dht

import java.net.InetSocketAddress
import java.time.Clock

import biton.dht.protocol.KMessage._
import biton.dht.protocol.{
  InfoHash,
  KMessage,
  KMessageSocket,
  Peer,
  RpcError,
  RpcErrorCode,
  Secret,
  Token,
  Transaction
}
import biton.dht.types.{ Node, NodeId }
import cats.effect.concurrent.Ref
import cats.effect.{ Concurrent, ContextShift, Fiber, IO, Resource, Timer }
import cats.syntax.apply._
import cats.syntax.applicative._
import cats.syntax.option._
import cats.syntax.functor._
import cats.syntax.eq._
import cats.syntax.flatMap._
import com.comcast.ip4s.{ IpAddress, Port }
import fs2.Stream
import fs2.io.udp.SocketGroup
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration.FiniteDuration

trait Server {
  def start(): Stream[IO, Unit]
}

object Server {
  val logger = Slf4jLogger.getLogger[IO]

  def apply(
      id: NodeId,
      table: TableState,
      store: PeerStore,
      secrets: Secrets,
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
    def validateToken(token: Token, ipAddress: IpAddress, t: Transaction)(
        ifValid: Token => IO[KMessage]
    ): IO[KMessage] = {
      for {
        (sec1, sec2) <- secrets.both
        valid = Token.create(ipAddress, sec1) === token || Token
          .create(ipAddress, sec2) === token
        msg <- if (valid) ifValid(token)
        else
          RpcErrorMessage(t, RpcError(RpcErrorCode.`203`, "Invalid token")).pure
      } yield msg
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
                  _       <- logger.debug("GetPeers")
                  _       <- addNode(senderId, remote)
                  peers   <- store.get(infohash)
                  nodes   <- table.neighbors(infohash)
                  secret  <- secrets.get
                  contact <- IO.fromEither(remote.toContact)
                  _ <- s.write1(
                    remote,
                    NodesWithPeersResponse(
                      t,
                      id,
                      Token.create(contact.ip, secret),
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
                  _       <- logger.debug("AnnouncePeer")
                  contact <- IO.fromEither(remote.toContact)
                  msg <- validateToken(token, contact.ip, t) { _ =>
                    for {
                      _ <- addNode(senderId, remote)
                      peer <- IO.fromEither(
                        impliedPort
                          .implied(remote.toPeer(port), remote.toPeer())
                      )
                      _ <- store.add(infoHash, peer)
                    } yield NodeIdResponse(t, id)
                  }
                  _ <- s.write1(remote, msg)
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

trait Secrets {
  def get: IO[Secret]
  def both: IO[(Secret, Secret)]
}

object Secrets {

  def create(validTime: FiniteDuration)(
      implicit timer: Timer[IO],
      cs: ContextShift[IO],
      con: Concurrent[IO]
  ): Resource[IO, Secrets] = {
    val secretsResRef = Ref[IO].of((Secret.gen, Secret.gen)).map { ref =>
      case class SecretsRes(refreshFiber: Option[Fiber[IO, Unit]])
          extends Secrets {
        override def get: IO[Secret]            = ref.get.map { case (sec1, _) => sec1 }
        override def both: IO[(Secret, Secret)] = ref.get

        def stop(): IO[Unit] =
          refreshFiber.fold(IO(()))(_.cancel)

        def start(): IO[SecretsRes] =
          for {
            f <- refresh().start
          } yield SecretsRes(f.some)

        def refresh(): IO[Unit] =
          timer.sleep(validTime) >> update() >> refresh()

        def update(): IO[Unit] = {
          ref.modify {
            case (_, sec2) =>
              val result = (sec2, Secret.gen)
              (result, result -> ())
          }
        }

      }
      SecretsRes(None)
    }
    Resource.make(secretsResRef.flatMap(_.start()))(_.stop())
  }
}
