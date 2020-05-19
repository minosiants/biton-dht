package kademlia

import java.net.InetSocketAddress

import benc.{ BCodec, BDecoder, BEncoder, BType, BencError }
import cats.effect.{ Concurrent, ContextShift, IO, Resource }
import com.comcast.ip4s.{ IpAddress, Port }
import fs2.io.udp.{ Packet, Socket, SocketGroup }
import io.estatico.newtype.macros.newtype
import kademlia.types._
import scodec.bits.BitVector
import cats.syntax.either._
import cats.instances.either._
import benc._
import cats.{ Eq, Show }
import scodec.{ Attempt, Codec, DecodeResult, Err }
import scodec.codecs._
import cats.syntax.flatMap._
import cats.syntax.apply._
import fs2._
import fs2.concurrent.Queue
import scodec.stream.{ StreamDecoder, StreamEncoder }
import cats.syntax.show._
import scala.concurrent.duration._
object protocol {

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
  }
  final case class RpcError(code: RpcErrorCode, msg: String)

  object RpcError {

    implicit val eqRpcError: Eq[RpcError] = Eq.fromUniversalEquals

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
  @newtype final case class InfoHash(value: BitVector)

  object InfoHash {
    implicit val codec: BCodec[InfoHash] =
      BCodec.bitVectorBCodec.xmap(InfoHash(_), _.value)
  }

  @newtype final case class Token(value: BitVector)

  object Token {
    //
    def gen(): Token = Token(Random.shortBinStr)

    implicit val codec: BCodec[Token] =
      BCodec.bitVectorBCodec.xmap(Token(_), _.value)
  }

  final case class Peer(ip: IpAddress, port: Port)

  object Peer {

    implicit val codec: Codec[Peer] = (
      ("ip" | ipAddressScocec) ::
        ("port" | portScodec)
    ).as[Peer]

    implicit val bdecoder: BDecoder[Peer] = BDecoder.sc[Peer]
    implicit val bencoder: BEncoder[Peer] = BEncoder.sc[Peer]
  }

  @newtype final case class Transaction(value: BitVector)
  object Transaction {

    def gen(): Transaction = {
      Transaction(Random.`2chars`)
    }

    implicit val eqTransaction: Eq[Transaction] = Eq.fromUniversalEquals

    implicit val bencoder: BEncoder[Transaction] =
      BEncoder.bitVectorBEncoder.contramap(_.value)
    implicit val bdecoder: BDecoder[Transaction] =
      BDecoder.bitVectorBDecoder.map(Transaction(_))
  }

  sealed abstract class KMessage {
    def t: Transaction
  }

  object KMessage {

    def getField[A: BDecoder](down: String)(name: String): BDecoder[A] =
      BDecoder.down(down).emap(_.get[A](name))
    def getAField[A: BDecoder](name: String): BDecoder[A] =
      getField[A]("a")(name)
    def getRField[A: BDecoder](name: String): BDecoder[A] =
      getField[A]("r")(name)

    implicit val eqKmessage: Eq[KMessage]     = Eq.fromUniversalEquals
    implicit val showKmessage: Show[KMessage] = Show.fromToString

    implicit val fieldName: FieldName = FieldName.snakeCaseFieldName

    final case class Ping(t: Transaction, id: NodeId) extends KMessage

    object Ping {
      implicit val bencoder: BEncoder[Ping] = BCodec[Ping]

      implicit val bdecoder: BDecoder[Ping] = for {
        t      <- BDecoder.at[Transaction]("t")
        nodeId <- getAField[NodeId]("id")
      } yield Ping(t, nodeId)

      implicit val eqPing: Eq[Ping] = Eq.fromUniversalEquals
    }

    final case class FindNode(t: Transaction, id: NodeId, target: NodeId)
        extends KMessage

    object FindNode {
      implicit val bencoder: BEncoder[FindNode] = BCodec[FindNode]

      implicit val bdecoder: BDecoder[FindNode] = for {
        t      <- BDecoder.at[Transaction]("t")
        id     <- getAField[NodeId]("id")
        target <- getAField[NodeId]("target")
      } yield FindNode(t, id, target)

      implicit val eqFindNode: Eq[FindNode] = Eq.fromUniversalEquals
    }

    final case class GetPeers(
        t: Transaction,
        id: NodeId,
        infoHash: InfoHash
    ) extends KMessage

    object GetPeers {
      implicit val bencoder: BEncoder[GetPeers] = BCodec[GetPeers]
      implicit val bdecoder: BDecoder[GetPeers] = for {
        t        <- BDecoder.at[Transaction]("t")
        id       <- getAField[NodeId]("id")
        infoHash <- getAField[InfoHash]("info_hash")
      } yield GetPeers(t, id, infoHash)

      implicit val eqGetPeers: Eq[GetPeers] = Eq.fromUniversalEquals
    }

    @newtype final case class ImpliedPort(value: Boolean)
    object ImpliedPort {
      implicit val bcodec: BCodec[ImpliedPort] = BCodec.intBCodec.xmap(
        i => ImpliedPort(i > 0),
        a => if (a.value) 1 else 0
      )
    }
    final case class AnnouncePeer(
        t: Transaction,
        impliedPort: ImpliedPort,
        id: NodeId,
        infoHash: InfoHash,
        port: Port,
        token: Token
    ) extends KMessage

    object AnnouncePeer {
      implicit val bencoder: BEncoder[AnnouncePeer] = BCodec[AnnouncePeer]
      implicit val bdecoder: BDecoder[AnnouncePeer] = for {
        t           <- BDecoder.at[Transaction]("t")
        impliedPort <- getAField[ImpliedPort]("implied_port")
        id          <- getAField[NodeId]("id")
        infoHash    <- getAField[InfoHash]("info_hash")
        port        <- getAField[Port]("port")
        token       <- getAField[Token]("token")
      } yield AnnouncePeer(t, impliedPort, id, infoHash, port, token)

      implicit val eqAnnouncePeer: Eq[AnnouncePeer] = Eq.fromUniversalEquals
    }

    final case class RpcErrorMessage(
        t: Transaction,
        e: RpcError
    ) extends KMessage

    object RpcErrorMessage {

      implicit val bdecoder: BDecoder[RpcErrorMessage] =
        BCodec[RpcErrorMessage]
      implicit val bencoder: BEncoder[RpcErrorMessage] =
        BCodec[RpcErrorMessage]

      implicit val eqRpcErrorMessage: Eq[RpcErrorMessage] =
        Eq.fromUniversalEquals
    }

    final case class NodeIdResponse(t: Transaction, id: NodeId) extends KMessage

    object NodeIdResponse {
      implicit val bencoder: BEncoder[NodeIdResponse] =
        BCodec[NodeIdResponse]

      implicit val bdecoder: BDecoder[NodeIdResponse] = for {
        t  <- BDecoder.at[Transaction]("t")
        id <- getRField[NodeId]("id")
      } yield NodeIdResponse(t, id)

      implicit val eqNodeIdResponse: Eq[NodeIdResponse] = Eq.fromUniversalEquals
    }

    final case class FindNodeResponse(
        t: Transaction,
        id: NodeId,
        nodes: List[Node]
    ) extends KMessage

    object FindNodeResponse {
      implicit val bencoder: BEncoder[FindNodeResponse] =
        BCodec[FindNodeResponse]
      implicit val bdecoder: BDecoder[FindNodeResponse] = for {
        t     <- BDecoder.at[Transaction]("t")
        id    <- getRField[NodeId]("id")
        nodes <- getRField[List[Node]]("nodes")
      } yield FindNodeResponse(t, id, nodes)

      implicit val eqFindNodeResponse: Eq[FindNodeResponse] =
        Eq.fromUniversalEquals
    }

    final case class GetPeersNodesResponse(
        t: Transaction,
        id: NodeId,
        token: Token,
        nodes: List[Node]
    ) extends KMessage

    object GetPeersNodesResponse {

      implicit val bencoder: BEncoder[GetPeersNodesResponse] =
        BCodec[GetPeersNodesResponse]

      implicit val bdecoder: BDecoder[GetPeersNodesResponse] = for {
        t     <- BDecoder.at[Transaction]("t")
        id    <- getRField[NodeId]("id")
        token <- getRField[Token]("token")
        nodes <- getRField[List[Node]]("nodes")
      } yield GetPeersNodesResponse(t, id, token, nodes)

      implicit val eqGetPeerNodesResponse: Eq[GetPeersNodesResponse] =
        Eq.fromUniversalEquals
    }

    final case class GetPeersResponse(
        t: Transaction,
        id: NodeId,
        token: Token,
        values: List[Peer]
    ) extends KMessage

    object GetPeersResponse {

      implicit val bencoder: BEncoder[GetPeersResponse] =
        BCodec[GetPeersResponse]

      implicit val bdecoder: BDecoder[GetPeersResponse] = for {
        t     <- BDecoder.at[Transaction]("t")
        id    <- getRField[NodeId]("id")
        token <- getRField[Token]("token")
        peers <- getRField[List[Peer]]("values")
      } yield GetPeersResponse(t, id, token, peers)

      implicit val eqGetPeersResponse: Eq[GetPeersResponse] =
        Eq.fromUniversalEquals
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
          case "r" =>
            v.as[GetPeersNodesResponse] orElse v.as[GetPeersResponse] orElse v
              .as[FindNodeResponse] orElse v.as[NodeIdResponse]

          case "e" => v.as[RpcErrorMessage]
          case m =>
            BencError.CodecError(s"$m Unsupported message type ").asLeft
        }
    )

    implicit val bencoder: BEncoder[KMessage] = {

      def query(qr: String, t: Transaction)(
          bt: BType
      ): Either[BencError, BType] = {
        (bt - "t").map { v =>
          BType.map(
            ("t", BType.bits(t.value)),
            ("y", BType.string("q")),
            ("q", BType.string(qr)),
            ("a", v)
          )
        }
      }

      def error(t: Transaction)(bt: BType): Either[BencError, BType] = {
        bt.field("e")
          .toRight(BencError.CodecError("Error field not found"))
          .map { v =>
            BType.map(
              ("t", BType.bits(t.value)),
              ("y", BType.string("e")),
              ("e", v)
            )
          }
      }

      def response(t: Transaction)(bt: BType): Either[BencError, BType] = {
        (bt - "t").map { v =>
          BType.map(
            ("t", BType.bits(t.value)),
            ("y", BType.string("r")),
            ("r", v)
          )
        }
      }
      BEncoder.instance {
        case p @ Ping(t, _) =>
          p.asBType >>= query("ping", t)

        case fn @ FindNode(t, _, _) =>
          fn.asBType >>= query("find_node", t)

        case gp @ GetPeers(t, _, _) =>
          gp.asBType >>= query("get_peers", t)

        case ap @ AnnouncePeer(t, _, _, _, _, _) =>
          ap.asBType >>= query("announce_peer", t)

        case re @ RpcErrorMessage(t, _) =>
          re.asBType >>= error(t)

        case nir @ NodeIdResponse(t, _) =>
          nir.asBType >>= response(t)

        case fn @ FindNodeResponse(t, _, _) =>
          fn.asBType >>= response(t)

        case nr @ GetPeersNodesResponse(t, _, _, _) =>
          nr.asBType >>= response(t)

        case pr @ GetPeersResponse(t, _, _, _) =>
          pr.asBType >>= response(t)
      }
    }

    val codec: Codec[KMessage] = Codec(
      a =>
        Attempt.fromEither(Benc.toBenc[KMessage](a).leftMap(e => Err(e.show))),
      bits =>
        Attempt.fromEither(
          Benc
            .fromBenc[KMessage](bits)
            .map(DecodeResult(_, BitVector.empty)) leftMap (e => Err(e.show))
        )
    )
  }

  type KPacket = (InetSocketAddress, KMessage)
  trait KMessageSocket {
    def read: Stream[IO, KPacket]
    def write1(remote: InetSocketAddress, msg: KMessage): IO[Unit]
  }

  object KMessageSocket {

    def apply(socket: Socket[IO], outputBound: Int = 1024)(
        implicit c: Concurrent[IO]
    ): IO[KMessageSocket] =
      for {
        outgoing <- Queue.bounded[IO, KPacket](outputBound)
      } yield new KMessageSocket {

        override def read: Stream[IO, KPacket] = {
          val readSocket = socket
            .reads()
            .flatMap { packet =>
              Stream
                .chunk(packet.bytes)
                .through(StreamDecoder.many(KMessage.codec).toPipeByte[IO])
                .map((packet.remote, _))
            }

          val writeOutput = outgoing.dequeue
            .flatMap {
              case (remote, msg) =>
                Stream
                  .emit(msg)
                  .through(StreamEncoder.many(KMessage.codec).toPipeByte[IO])
                  .chunks
                  .map(data => Packet(remote, data))
            }
            .through(socket.writes())

          readSocket.concurrently(writeOutput)
        }

        override def write1(
            remote: InetSocketAddress,
            msg: KMessage
        ): IO[Unit] = outgoing.enqueue1((remote, msg))
      }

    def createSocket(sg: SocketGroup, port: Option[Port] = None)(
        implicit c: Concurrent[IO],
        cs: ContextShift[IO]
    ): Stream[IO, KMessageSocket] = {

      val logSocket = Resource.make(IO(println("new socket")))(
        _ => IO(println("closed socket"))
      )

      Stream
        .resource {
          sg.open(new InetSocketAddress(port.map(_.value).getOrElse(0)))
        }
        .flatMap(socket => Stream.eval(KMessageSocket(socket)))

    }
  }

}
