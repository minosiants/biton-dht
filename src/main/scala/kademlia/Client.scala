package kademlia

import java.net.InetSocketAddress
import java.nio.channels.InterruptedByTimeoutException

import cats.syntax.eq._
import cats.effect.{ Concurrent, ContextShift, IO }
import com.comcast.ip4s.Port
import fs2.io.udp.SocketGroup
import kademlia.protocol.{
  InfoHash,
  KMessage,
  KMessageSocket,
  KPacket,
  Peer,
  RpcError,
  Token,
  Transaction
}
import kademlia.protocol.KMessage._
import kademlia.types._
import fs2._
import _root_.io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import cats.Show
import cats.syntax.apply._
import cats.syntax.show._
import cats.instances.list._
import scala.concurrent.duration._
import cats.syntax.either._

trait Client {

  def ping(node: Node): Stream[IO, NodeIdResponse]
  def pingF(node: Node): IO[Option[NodeIdResponse]] = Client.extract(ping(node))
  def findNode(contact: Contact, target: NodeId): Stream[IO, FindNodeResponse]
  def findNodeF(
      contact: Contact,
      target: NodeId
  ): IO[Option[FindNodeResponse]] =
    Client.extract(findNode(contact, target))
  def getPeers(
      contact: Contact,
      infoHash: InfoHash
  ): Stream[IO, Either[GetPeersNodesResponse, GetPeersResponse]]

  def announcePeer(
      contact: Contact,
      impliedPort: ImpliedPort,
      infoHash: InfoHash,
      port: Port,
      token: Token
  ): Stream[IO, NodeIdResponse]

  def announcePeerF(
      contact: Contact,
      impliedPort: ImpliedPort,
      infoHash: InfoHash,
      port: Port,
      token: Token
  ): IO[Option[NodeIdResponse]] =
    Client.extract(announcePeer(contact, impliedPort, infoHash, port, token))

}

object Client {
  val logger = Slf4jLogger.getLogger[IO]

  def apply(
      id: NodeId,
      sg: SocketGroup,
      readTimeout: Option[FiniteDuration] = Some(2.seconds)
  )(
      implicit c: Concurrent[IO],
      cs: ContextShift[IO]
  ) = {
    val logger = Slf4jLogger.getLogger[IO]

    def remote(contact: Contact) =
      new InetSocketAddress(contact.ip.toInetAddress, contact.port.value)
    def socket = KMessageSocket.createSocket(sg, readTimeout)

    type RespFunc[A <: KMessage] = PartialFunction[KPacket, IO[A]]

    def get[A <: KMessage](contact: Contact, msg: KMessage)(
        pf: RespFunc[A]
    ): Stream[IO, A] = {
      val badTransactionId: RespFunc[A] = {
        case (_, resp) if msg.t =!= resp.t =>
          IO.raiseError(
            Error.ClientError(
              s"transaction does't much. Expected: ${msg.t}. Actual: ${resp.t}"
            )
          )
      }
      val badResponse: RespFunc[A] = {
        case (_, RpcErrorMessage(_, e @ RpcError(_, _))) =>
          IO.raiseError(Error.KRPCError(e.show))
        case (_, res) =>
          IO.raiseError(Error.ClientError(s"Unexpected response. $res"))
      }

      socket
        .flatMap { s =>
          Stream.eval_(s.write1(remote(contact), msg)).drain ++
            Stream.eval_(logger.debug("write1 done")) ++
            s.read
        }
        .evalMap {
          badTransactionId orElse pf orElse badResponse
        }
        .head
        .handleErrorWith {
          case _: InterruptedByTimeoutException =>
            Stream.eval_(logger.error(s"Timeout")) ++ Stream.empty
          case e @ Error.KRPCError(_) =>
            Stream.eval_(logger.error(Show[Error].show(e))) ++
              Stream.eval_(logger.debug(s" message: $msg")) ++
              Stream.empty
          case e @ Error.ClientError(_) =>
            Stream.eval_(logger.error(Show[Error].show(e))) ++
              Stream.eval_(logger.debug(s" message: $msg")) ++
              Stream.empty
          case e: Throwable =>
            Stream.eval_(logger.error(e.getMessage)) ++
              Stream.eval_(logger.debug(s"message: $msg")) ++
              Stream.empty
        }
    }

    new Client() {
      override def ping(node: Node): Stream[IO, NodeIdResponse] = {
        get[NodeIdResponse](node.contact, Ping(Transaction.gen(), node.nodeId)) {
          case (_, r @ NodeIdResponse(_, _)) =>
            logger.debug(Show[KMessage].show(r)) *>
              IO(r)
        }
      }

      override def findNode(
          contact: Contact,
          target: NodeId
      ): Stream[IO, FindNodeResponse] = {
        val fn = FindNode(Transaction.gen(), id, target)
        get[FindNodeResponse](contact, fn) {
          case (_, r @ FindNodeResponse(_, _, _)) =>
            IO(r)
        }
      }

      override def getPeers(
          contact: Contact,
          infoHash: InfoHash
      ): Stream[IO, Either[GetPeersNodesResponse, GetPeersResponse]] = {
        val req = GetPeers(Transaction.gen(), id, infoHash)
        get[KMessage](contact, req) {
          case (_, r @ GetPeersResponse(_, _, _, _)) =>
            IO(r)
          case (_, r @ GetPeersNodesResponse(_, _, _, _)) =>
            IO(r)
        }.flatMap {
          case r @ GetPeersNodesResponse(_, _, _, _) =>
            Stream.emit(r.asLeft[GetPeersResponse])
          case r @ GetPeersResponse(_, _, _, _) =>
            Stream.emit(r.asRight[GetPeersNodesResponse])
          case v =>
            Stream.raiseError(Error.ClientError(s"Should not get $v here"))
        }
      }

      override def announcePeer(
          contact: Contact,
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
        get[NodeIdResponse](contact, req) {
          case (_, r @ NodeIdResponse(_, _)) =>
            IO(r)
        }
      }

    }
  }
  def extract[A](s: Stream[IO, A]): IO[Option[A]] =
    s.compile.toList.map(_.headOption)

  def extractStrict[A](s: Stream[IO, A]): IO[A] =
    s.compile.toList.flatMap(
      _.headOption.fold(
        IO.raiseError[A](Error.ServerError("Not found"))
      )(v => IO(v))
    )

}
