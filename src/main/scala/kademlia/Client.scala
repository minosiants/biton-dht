package kademlia

import java.net.InetSocketAddress

import cats.syntax.eq._
import cats.effect.{ Concurrent, ContextShift, IO }
import com.comcast.ip4s.Port
import fs2.io.udp.SocketGroup
import kademlia.protocol.{
  InfoHash,
  KMessage,
  KMessageSocket,
  KPacket,
  Token,
  Transaction
}
import kademlia.protocol.KMessage._
import kademlia.types._
import fs2._
import _root_.io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import cats.Show
import cats.syntax.apply._

trait Client {

  def ping(id: NodeId): Stream[IO, NodeIdResponse]
  def pingF(id: NodeId): IO[NodeIdResponse] = Client.extract(ping(id))
  def findNode(target: NodeId): Stream[IO, FindNodeResponse]
  def findNodeF(target: NodeId): IO[FindNodeResponse] =
    Client.extract(findNode(target))
  def getPeers(infoHash: InfoHash): Stream[IO, GetPeersResponse]
  def getPeersF(infoHash: InfoHash): IO[GetPeersResponse] =
    Client.extract(getPeers((infoHash)))
  def announcePeer(
      impliedPort: ImpliedPort,
      infoHash: InfoHash,
      port: Port,
      token: Token
  ): Stream[IO, NodeIdResponse]

  def announcePeerF(
      impliedPort: ImpliedPort,
      infoHash: InfoHash,
      port: Port,
      token: Token
  ): IO[NodeIdResponse] =
    Client.extract(announcePeer(impliedPort, infoHash, port, token))
}

object Client {

  def apply(id: NodeId, target: Node, sg: SocketGroup)(
      implicit c: Concurrent[IO],
      cs: ContextShift[IO]
  ) = {
    val logger = Slf4jLogger.getLogger[IO]

    val remote =
      new InetSocketAddress(target.ip.toInetAddress, target.port.value)
    def socket = KMessageSocket.createSocket(sg)

    type RespFunc[A <: KMessage] = PartialFunction[KPacket, IO[A]]

    def get[A <: KMessage](msg: KMessage)(pf: RespFunc[A]): Stream[IO, A] = {
      val badTransactionId: RespFunc[A] = {
        case (_, resp) if msg.t =!= resp.t =>
          IO.raiseError(
            Error.ClientError(
              s"transaction does't much. Expected: ${msg.t}. Actual: ${resp.t}"
            )
          )
      }
      val badResponse: RespFunc[A] = {
        case (_, res) =>
          IO.raiseError(Error.ClientError(s"Unexpected response. $res"))
      }

      socket
        .flatMap { s =>
          Stream.eval_(s.write1(remote, msg)).drain ++
            s.read
        }
        .evalMap {
          badTransactionId orElse pf orElse badResponse
        }
        .head
    }

    new Client() {
      override def ping(id: NodeId): Stream[IO, NodeIdResponse] = {
        get[NodeIdResponse](Ping(Transaction.gen(), id)) {
          case (_, r @ NodeIdResponse(_, _)) =>
            logger.debug(Show[KMessage].show(r)) *>
              IO(r)
        }
      }

      override def findNode(target: NodeId): Stream[IO, FindNodeResponse] = {
        val fn = FindNode(Transaction.gen(), id, target)
        get[FindNodeResponse](fn) {
          case (_, r @ FindNodeResponse(_, _, _)) =>
            IO(r)
        }
      }

      override def getPeers(
          infoHash: InfoHash
      ): Stream[IO, GetPeersResponse] = {
        val req = GetPeers(Transaction.gen(), id, infoHash)
        get[GetPeersResponse](req) {
          case (_, r @ GetPeersResponse(_, _, _, _)) =>
            IO(r)
        }
      }

      override def announcePeer(
          impliedPort: ImpliedPort,
          infoHash: InfoHash,
          port: Port,
          token: Token
      ): Stream[IO, NodeIdResponse] = {
        val req = AnnouncePeer(
          Transaction.gen(),
          impliedPort,
          id,
          infoHash,
          port,
          token
        )
        get[NodeIdResponse](req) {
          case (_, r @ NodeIdResponse(_, _)) =>
            IO(r)
        }
      }
    }
  }
  def extract[A](s: Stream[IO, A]): IO[A] =
    s.compile.toList.flatMap(
      _.headOption.fold(
        IO.raiseError[A](Error.ServerError("Not found"))
      )(v => IO(v))
    )
}
