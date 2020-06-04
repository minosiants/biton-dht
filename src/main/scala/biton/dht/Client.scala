package biton.dht

import java.net.InetSocketAddress

import biton.dht
import biton.dht.protocol.KMessage._
import biton.dht.protocol._
import biton.dht.types._
import cats.Show
import cats.effect.{ Concurrent, ContextShift, IO, Timer }
import cats.implicits._
import com.comcast.ip4s.Port
import fs2.io.udp.SocketGroup
import fs2.{ RaiseThrowable, Stream }
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration._
import scala.util.control.NonFatal

trait Client
    extends Client.Ping
    with Client.FindNode
    with Client.GetPeers
    with Client.AnnouncePeer

object Client {

  trait Ping {
    def ping(node: Node): Stream[IO, NodeIdResponse]
  }
  trait FindNode {
    def findNode(contact: Contact, target: NodeId): Stream[IO, List[Node]]
  }
  trait GetPeers {
    def getPeers(
        node: Node,
        infoHash: InfoHash
    ): Stream[IO, NodeResponse]
  }

  trait AnnouncePeer {
    def announcePeer(
        n: Node,
        token: Token,
        infoHash: InfoHash,
        port: Port
    ): Stream[IO, NodeIdResponse]
  }

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
            dht.Error.ClientError(
              s"transaction does't much. Expected: ${msg.t}. Actual: ${resp.t}"
            )
          )
      }
      val badResponse: RespFunc[A] = {
        case (_, RpcErrorMessage(_, e @ RpcError(_, _))) =>
          IO.raiseError(dht.Error.KRPCError(e.show))
        case (_, res) =>
          IO.raiseError(dht.Error.ClientError(s"Unexpected response. $res"))
      }

      socket
        .flatMap { s =>
          Stream.eval_(s.write1(remote(contact), msg)).drain ++
            Stream.eval_(logger.debug("write1 done")) ++
            s.read
        }
        .evalMap {
          //badTransactionId orElse pf orElse badResponse
          pf orElse badResponse
        }
        .head
        .attempts(Stream(1.second).repeatN(2))
        .takeThrough(_.fold(NonFatal(_), _ => false))
        .last
        .map(_.get)
        .rethrow
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
          case (_, r @ NodesWithPeersResponse(_, _, _, _, _)) =>
            IO(r)

        }.flatMap {
            case NodesWithPeersResponse(_, _, token, nodes, peers) =>
              Stream.eval_(logger.error(s"!!! peers: $peers")) ++
                Stream.emit(
                  dht.NodeResponse(
                    NodeInfo(token, node),
                    nodes.toList.flatten,
                    peers.toList.flatten
                  )
                )
            case v =>
              Stream.raiseError(
                dht.Error.ClientError(s"Should not get $v here")
              )
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
          n: Node,
          token: Token,
          infoHash: InfoHash,
          port: Port
      ): Stream[IO, NodeIdResponse] = {
        val req = AnnouncePeer(
          Transaction.gen(),
          ImpliedPort(true),
          id,
          infoHash,
          port,
          token
        )
        get[NodeIdResponse](n.contact, req) {
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
