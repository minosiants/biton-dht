package kademlia

import java.net.InetSocketAddress
import java.nio.channels.InterruptedByTimeoutException

import cats.effect.{ Concurrent, ContextShift, IO, Timer }
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
import cats.implicits._
import _root_.io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import cats.Show

import scala.concurrent.duration._

import scala.util.control.NonFatal

trait Client {

  def ping(node: Node): Stream[IO, NodeIdResponse]
  def findNode(contact: Contact, target: NodeId): Stream[IO, List[Node]]
  def getPeers(
      node: Node,
      infoHash: InfoHash
  ): Stream[IO, NodeResponse]

  def announcePeer(
      n: NodeInfo,
      infoHash: InfoHash,
      port: Port
  ): Stream[IO, NodeIdResponse]

}

object Client {
  val logger = Slf4jLogger.getLogger[IO]

  def apply(
      id: NodeId,
      sg: SocketGroup,
      readTimeout: Option[FiniteDuration] = Some(2.seconds)
  )(
      implicit c: Concurrent[IO],
      cs: ContextShift[IO],
      rt: RaiseThrowable[IO],
      timer: Timer[IO]
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
        .attempts(Stream(1.second).repeatN(2))
        .takeThrough(_.fold(NonFatal(_), _ => false))
        .last
        .map(_.get)
        .rethrow
      /*.handleErrorWith {
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
        }*/
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
      ): Stream[IO, List[Node]] = {
        val fn = FindNode(Transaction.gen(), id, target)
        get[FindNodeResponse](contact, fn) {
          case (_, r @ FindNodeResponse(_, _, _)) =>
            IO(r)
        }.map(_.nodes)
          .attempt
          .evalTap {
            case Left(error) =>
              logger.debug(
                s"Find node error. nodeId: $target contact:$contact ${error.string}"
              )
            case Right(_) => IO(())
          }
          .rethrow
      }

      override def getPeers(
          node: Node,
          infoHash: InfoHash
      ): Stream[IO, NodeResponse] = {
        val req = GetPeers(Transaction.gen(), id, infoHash)
        get[KMessage](node.contact, req) {
          case (_, r @ GetPeersResponse(_, _, _, _)) =>
            IO(r)
          case (_, r @ GetPeersNodesResponse(_, _, _, _)) =>
            IO(r)
        }.flatMap {
            case GetPeersNodesResponse(_, _, token, nodes) =>
              Stream.emit(
                NodeResponse(
                  NodeInfo(token, node, id.distance(node.nodeId)),
                  nodes,
                  Nil
                )
              )
            case GetPeersResponse(_, _, token, peers) =>
              Stream.emit(
                NodeResponse(
                  NodeInfo(token, node, id.distance(node.nodeId)),
                  Nil,
                  peers
                )
              )
            case v =>
              Stream.raiseError(Error.ClientError(s"Should not get $v here"))
          }
          .attempt
          .evalTap {
            case Left(error) =>
              logger.debug(s"Get peers error. node: $node ${error.string}")
            case Right(_) => IO(())
          }
          .rethrow

      }

      override def announcePeer(
          n: NodeInfo,
          infoHash: InfoHash,
          port: Port
      ): Stream[IO, NodeIdResponse] = {
        val req = AnnouncePeer(
          Transaction.gen(),
          ImpliedPort(true),
          id,
          infoHash,
          port,
          n.token
        )
        get[NodeIdResponse](n.node.contact, req) {
          case (_, r @ NodeIdResponse(_, _)) =>
            IO(r)
        }.attempt.evalTap {
          case Left(error) =>
            logger.debug(s"Announce peer error. node: $n ${error.string}")
          case Right(_) => IO(())
        }.rethrow
      }
    }
  }

}

final case class NodeResponse(
    info: NodeInfo,
    nodes: List[Node],
    peers: List[Peer]
)
