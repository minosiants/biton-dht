package kademlia

import cats.effect.IO
import com.comcast.ip4s.IpAddress
import fs2.io.udp.SocketGroup
import io.estatico.newtype.macros.newtype
import kademlia.types._
import scodec.bits.BitVector

object protocol {
  sealed abstract class NodeStatus extends Product with Serializable

  object NodeStatus {
    final case object Online  extends NodeStatus
    final case object Offline extends NodeStatus
  }
  sealed abstract class KResponse

  object KResponse {
    final case class NodesResponse(nodes: List[Node]) extends KResponse
    final case class StoredValue(value: Value)        extends KResponse
  }
  trait Rpc {
    def ping(node: Node): IO[NodeStatus]
    def store(key: Key, value: Value): IO[Unit]
    def findNode(nodeId: NodeId): IO[List[Node]]
    def findValue(key: Key): IO[KResponse]

  }

  final case class RpcErrorCode(code: Int, msg: String)
      extends Product
      with Serializable
  object RpcErrorCode {
    val `201` = RpcErrorCode(201, "Generic Error")
    val `202` = RpcErrorCode(202, "Server Error")
    val `203` = RpcErrorCode(203, "Protocol Error")
    val `204` = RpcErrorCode(204, "Method Unknown")

    val codes                                 = List(`201`, `202`, `203`, `204`)
    def find(code: Int): Option[RpcErrorCode] = codes.find(_.code == code)
  }
  final case class RpcError(code: RpcErrorCode, msg: String)
  sealed abstract class KMessage {
    def id: String
  }

  @newtype final case class InfoHash(value: BitVector)
  @newtype final case class Token(value: BitVector)
  @newtype final case class Peer(ip: IpAddress, port: Int)

  object KMessage {
    final case class Ping(id: String, nodeId: NodeId) extends KMessage
    final case class FindNode(id: String, me: NodeId, target: NodeId)
        extends KMessage
    final case class GetPeers(id: String, nodeId: NodeId, infoHash: InfoHash)
        extends KMessage
    final case class AnnouncePeer(
        id: String,
        impliedPort: Boolean,
        nodeId: NodeId,
        infoHash: InfoHash,
        token: Token
    ) extends KMessage
    final case class RpcErrorMessage(id: String, errors: List[RpcError])
        extends KMessage
    final case class NodeIdResponse(id: String, nodeId: NodeId) extends KMessage
    final case class NodeResponse(id: String, nodes: Node)      extends KMessage
    final case class NodesResponse(
        id: String,
        nodeId: NodeId,
        token: Token,
        nodes: List[Node]
    ) extends KMessage
    final case class PeersResponse(
        id: String,
        nodeId: NodeId,
        token: Token,
        nodes: List[Peer]
    ) extends KMessage
  }

  trait ProtocolCodec {}

  object Rpc {
    def apply(sg: SocketGroup): Rpc = new Rpc {
      override def ping(node: Node): IO[NodeStatus] = ???

      override def store(key: Key, value: Value): IO[Unit] = ???

      override def findNode(nodeId: NodeId): IO[List[Node]] = ???

      override def findValue(key: Key): IO[KResponse] = ???
    }
  }
}
