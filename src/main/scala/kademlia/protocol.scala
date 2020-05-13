package kademlia

import benc.{ BCodec, BDecoder, BEncoder, BType, BencError, BencKey }
import cats.effect.IO
import com.comcast.ip4s.IpAddress
import fs2.io.udp.SocketGroup
import io.estatico.newtype.macros.newtype
import kademlia.types._
import scodec.bits.BitVector
import cats.syntax.either._
import benc._

object protocol {

  sealed abstract class NodeStatus extends Product with Serializable

  object NodeStatus {

    final case object Online extends NodeStatus

    final case object Offline extends NodeStatus

  }

  sealed abstract class KResponse

  object KResponse {

    final case class NodesResponse(nodes: List[Node]) extends KResponse

    final case class StoredValue(value: Value) extends KResponse

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

    val codes = List(`201`, `202`, `203`, `204`)

    def find(code: Int): Option[RpcErrorCode] = codes.find(_.code == code)

    implicit val bcodec: BCodec[RpcErrorCode] =
      BCodec.intBCodec.exmap[RpcErrorCode](
        c => find(c).toRight(BencError.CodecError(s"$c code is not found")),
        _.code.asRight
      )

    final case class RpcError(code: RpcErrorCode, msg: String)

    object RpcError {

      implicit val bdecoder: BDecoder[RpcError] = for {
        code <- BDecoder.at[RpcErrorCode](0)
        msg  <- BDecoder.at[String](1)
      } yield RpcError(code, msg)

      implicit val bencoder: BEncoder[RpcError] = BEncoder.instance[RpcError](
        v =>
          BType
            .list(BType.int(v.code.code), BType.string(v.msg))
            .asRight
      )

    }

    sealed abstract class KMessage {
      def t: String
    }

    @newtype final case class InfoHash(value: BitVector)

    object InfoHash {
      implicit val codec: BCodec[InfoHash] =
        BCodec.bitVectorBCodec.xmap(InfoHash(_), _.value)
    }

    @newtype final case class Token(value: BitVector)

    object Token {
      implicit val codec: BCodec[Token] =
        BCodec.bitVectorBCodec.xmap(Token(_), _.value)
    }

    final case class Peer(ip: IpAddress, port: Int)

    object Peer {
      implicit val codec: BCodec[Peer] = ???
    }

    object KMessage {

      def getField[A: BDecoder](name: String): BDecoder[A] =
        BDecoder.down("a").emap(_.get[A](name))

      final case class Ping(t: String, nodeId: NodeId) extends KMessage

      object Ping {
        implicit val bencoder: BEncoder[Ping] = BEncoder[Ping]

        implicit val bdecoder: BDecoder[Ping] = for {
          t      <- BDecoder.at[String]("t")
          nodeId <- BDecoder.down("a").emap(_.get[NodeId]("id"))
        } yield Ping(t, nodeId)

      }
      final case class FindNode(t: String, id: NodeId, target: NodeId)
          extends KMessage

      object FindNode {
        implicit val bencoder: BEncoder[FindNode] = BEncoder[FindNode]

        implicit val bdecoder: BDecoder[FindNode] = for {
          t      <- BDecoder.at[String]("t")
          id     <- getField[NodeId]("id")
          target <- getField[NodeId]("target")
        } yield FindNode(t, id, target)
      }

      final case class GetPeers(t: String, nodeId: NodeId, infoHash: InfoHash)
          extends KMessage

      object GetPeers {
        implicit val bencoder: BEncoder[GetPeers] = BEncoder[GetPeers]
        implicit val bdecoder: BDecoder[GetPeers] = for {
          t        <- BDecoder.at[String]("t")
          id       <- getField[NodeId]("id")
          infoHash <- getField[InfoHash]("info_hash")
        } yield GetPeers(t, id, infoHash)
      }

      final case class AnnouncePeer(
          t: String,
          impliedPort: Boolean,
          id: NodeId,
          infoHash: InfoHash,
          port: Int,
          token: Token
      ) extends KMessage

      object AnnouncePeer {
        implicit val bencoder: BEncoder[AnnouncePeer] = BEncoder[AnnouncePeer]
        implicit val bdecoder: BDecoder[AnnouncePeer] = for {
          t           <- BDecoder.at[String]("t")
          impliedPort <- getField[Boolean]("implied_port")
          id          <- getField[NodeId]("id")
          infoHash    <- getField[InfoHash]("info_hash")
          port        <- getField[Int]("port")
          token       <- getField[Token]("token")
        } yield AnnouncePeer(t, impliedPort, id, infoHash, port, token)
      }

      final case class RpcErrorMessage(t: String, @BencKey("e") error: RpcError)
          extends KMessage

      object RpcErrorMessage {

        implicit val bdecoder: BDecoder[RpcErrorMessage] =
          BDecoder[RpcErrorMessage]
        implicit val bencoder: BEncoder[RpcErrorMessage] =
          BEncoder[RpcErrorMessage]

      }

      final case class NodeIdResponse(t: String, nodeId: NodeId)
          extends KMessage
      object NodeIdResponse {
        implicit val bencoder: BEncoder[NodeIdResponse] =
          BEncoder[NodeIdResponse]
      }

      final case class NodeResponse(t: String, nodes: Node) extends KMessage

      object NodeResponse {
        implicit val bencoder: BEncoder[NodeResponse] = BEncoder[NodeResponse]
      }

      final case class NodesResponse(
          t: String,
          nodeId: NodeId,
          token: Token,
          nodes: List[Node]
      ) extends KMessage

      object NodesResponse {
        implicit val bencoder: BEncoder[NodesResponse] = BEncoder[NodesResponse]
      }

      final case class PeersResponse(
          t: String,
          nodeId: NodeId,
          token: Token,
          nodes: List[Peer]
      ) extends KMessage

      object PeersResponse {
        implicit val bencoder: BEncoder[PeersResponse] = BEncoder[PeersResponse]
      }

      implicit val bdecoder: BDecoder[KMessage] = BDecoder.instance(
        v =>
          v.get[String]("y").flatMap {
            case "q" =>
              v.get[String]("q").flatMap {
                case "ping"          => v.as[Ping]
                case "find_node"     => v.as[FindNode]
                case "get_peers"     => v.as[GetPeers]
                case "announce_peer" => v.as[AnnouncePeer]
                case q =>
                  BencError.CodecError(s"$q Unsupported query type ").asLeft
              }
            case "r" => ???
            case "e" => v.as[RpcErrorMessage]
            case m =>
              BencError.CodecError(s"$m Unsupported message type ").asLeft
          }
      )

      implicit val bencoder: BEncoder[KMessage] = {

        def query(qr: String)(bt: BType): BType = {
          BType.map(
            ("y", BType.string("q")),
            ("q", BType.string(qr)),
            ("a", bt)
          )
        }
        def error(bt: BType): BType = {
          BType.map(
            ("y", BType.string("e")),
            ("a", bt)
          )
        }
        def response(bt: BType): BType = {
          BType.map(
            ("y", BType.string("r")),
            ("r", bt)
          )
        }
        BEncoder.instance {
          case p @ Ping(_, _) =>
            p.asBType map query("ping")

          case fn @ FindNode(_, _, _) =>
            fn.asBType map query("find_node")

          case gp @ GetPeers(_, _, _) =>
            gp.asBType map query("get_peers")

          case ap @ AnnouncePeer(_, _, _, _, _, _) =>
            ap.asBType map query("announce_peer")

          case re @ RpcErrorMessage(_, _) =>
            re.asBType map error

          case nir @ NodeIdResponse(_, _) =>
            nir.asBType map response

          case nr @ NodeResponse(_, _) =>
            nr.asBType map response

          case nr @ NodesResponse(_, _, _, _) =>
            nr.asBType map response

          case pr @ PeersResponse(_, _, _, _) =>
            pr.asBType map response
        }
      }
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

}
