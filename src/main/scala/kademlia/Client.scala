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

trait Client {
  def ping(id: NodeId): Stream[IO, NodeIdResponse]
  def findNode(target: NodeId): Stream[IO, FindNodeResponse]
  def getPeers(infoHash: InfoHash): Stream[IO, GetPeersResponse]
  def announcePeer(
      impliedPort: ImpliedPort,
      infoHash: InfoHash,
      port: Port,
      token: Token
  ): Stream[IO, NodeIdResponse]
}

object Client {
  def apply(id: NodeId, target: Node, sg: SocketGroup)(
      implicit c: Concurrent[IO],
      cs: ContextShift[IO]
  ) = {

    val remote =
      new InetSocketAddress(target.ip.toInetAddress, target.port.value)
    val socket = KMessageSocket.createSocket(sg)

    type RespFunc[A <: KMessage] = PartialFunction[KPacket, Stream[IO, A]]

    def get[A <: KMessage](msg: KMessage)(pf: RespFunc[A]): Stream[IO, A] = {
      val badTransactionId: RespFunc[A] = {
        case (_, resp) if msg.t =!= resp.t =>
          Stream.raiseError(
            Error.ClientError(
              s"transaction does't much. Expected: ${msg.t}. Actual: ${resp.t}"
            )
          )
      }
      val badResponse: RespFunc[A] = {
        case (_, res) =>
          Stream.raiseError(Error.ClientError(s"Unexpected response. $res"))
      }

      socket
        .flatMap { s =>
          Stream.eval_(s.write1(remote, msg)).drain ++
            s.read
        }
        .flatMap {
          badTransactionId orElse pf orElse badResponse
        }
    }

    new Client() {
      override def ping(id: NodeId): Stream[IO, NodeIdResponse] = {
        get[NodeIdResponse](Ping(Transaction.gen(), id)) {
          case (_, r @ NodeIdResponse(_, _)) =>
            Stream.eval(IO(r))
        }
      }

      override def findNode(target: NodeId): Stream[IO, FindNodeResponse] = {
        val fn = FindNode(Transaction.gen(), id, target)
        get[FindNodeResponse](fn) {
          case (_, r @ FindNodeResponse(_, _, _)) =>
            Stream.eval(IO(r))
        }
      }

      override def getPeers(
          infoHash: InfoHash
      ): Stream[IO, GetPeersResponse] = {
        val req = GetPeers(Transaction.gen(), id, infoHash)
        get[GetPeersResponse](req) {
          case (_, r @ GetPeersResponse(_, _, _, _)) =>
            Stream.eval(IO(r))
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
            Stream.eval(IO(r))
        }
      }
    }
  }
}
