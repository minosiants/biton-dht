/*
 * Copyright 2020 Kaspar Minosiants
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package biton.dht

import java.net.InetSocketAddress

import benc.{ BCodec, BDecoder, BEncoder, BType, BencError, _ }
import biton.dht.types._
import cats.effect.{ Concurrent, ContextShift, IO, Resource }
import cats.instances.either._
import cats.instances.list._
import cats.instances.option._
import cats.syntax.either._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.show._
import cats.{ Eq, Show }
import com.comcast.ip4s.{ IpAddress, Port }
import fs2.concurrent.Queue
import fs2.io.udp.{ Packet, Socket, SocketGroup }
import fs2.{ Stream, hash }
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.estatico.newtype.macros.newtype
import scodec.bits.BitVector
import scodec.codecs._
import scodec.stream.{ StreamDecoder, StreamEncoder }
import scodec.{ Attempt, Codec, DecodeResult, Err }

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

    implicit val eqRpcError: Eq[RpcError]     = Eq.fromUniversalEquals
    implicit val showRpcError: Show[RpcError] = Show.fromToString

    implicit lazy val bdecoder: BDecoder[RpcError] = for {
      code <- BDecoder.at[RpcErrorCode](0)
      msg  <- BDecoder.at[String](1)
    } yield RpcError(code, msg)

    implicit lazy val bencoder: BEncoder[RpcError] =
      BEncoder.instance[RpcError](
        v =>
          BType
            .list(BType.int(v.code.code), BType.string(v.msg))
            .asRight
      )

  }
  @newtype final case class InfoHash(value: BitVector) {
    def toHex: String = value.toHex
  }

  object InfoHash {
    implicit lazy val codec: BCodec[InfoHash] =
      BCodec.bitVectorBCodec.xmap(InfoHash(_), _.value)

    implicit val eqInfoHash: Eq[InfoHash] = Eq.instance(_.value === _.value)
  }

  @newtype final case class Secret(value: BitVector)

  object Secret {

    def gen: Secret = Secret(Random.`40bytes`)

    implicit val codec: Codec[Secret] =
      bytes(40).xmap(v => Secret(v.bits), _.value.bytes)

    implicit val eqSecret: Eq[Secret] =
      Eq.instance((a, b) => a.value === b.value)
  }

  @newtype final case class Token(value: BitVector)

  object Token {
    val zero = Token(BitVector.fromInt(0))
    def create(ip: IpAddress, secret: Secret): Token = {
      val hashed = Stream
        .emits((BitVector(ip.toBytes) ++ secret.value).toByteArray)
        .through(hash.sha1)
        .through(hash.sha1)
        .compile
        .toList
      Token(BitVector(hashed))
    }

    implicit lazy val bcodec: BCodec[Token] =
      BCodec.bitVectorBCodec.xmap(Token(_), _.value)

    implicit val eqToken: Eq[Token] =
      Eq.instance((a, b) => a.value === b.value)
  }

  final case class Peer(ip: IpAddress, port: Port)

  object Peer {

    implicit val codec: Codec[Peer] = (
      ("ip" | ipAddressScocec) ::
        ("port" | portScodec)
    ).as[Peer]

    implicit lazy val bdecoder: BDecoder[Peer] = BDecoder.sc[Peer]
    implicit lazy val bencoder: BEncoder[Peer] = BEncoder.sc[Peer]

    implicit val eqPeer: Eq[Peer] =
      Eq.instance((a, b) => a.ip === b.ip && a.port === b.port)

  }

  @newtype final case class Transaction(value: BitVector)
  object Transaction {

    implicit val randomTransaction: Random[Transaction] = Random.instance(
      Transaction(Random.`2chars`)
    )

    implicit val eqTransaction: Eq[Transaction] = Eq.instance { (a, b) =>
      a.value === b.value
    }

    implicit lazy val bencoder: BEncoder[Transaction] =
      BEncoder.bitVectorBEncoder.contramap(_.value)
    implicit lazy val bdecoder: BDecoder[Transaction] =
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

    implicit val eqKmessage: Eq[KMessage] = Eq.instance {
      case (a: Ping, b: Ping)                                     => a === b
      case (a: FindNode, b: FindNode)                             => a === b
      case (a: GetPeers, b: GetPeers)                             => a === b
      case (a: AnnouncePeer, b: AnnouncePeer)                     => a === b
      case (a: RpcErrorMessage, b: RpcErrorMessage)               => a === b
      case (a: NodeIdResponse, b: NodeIdResponse)                 => a === b
      case (a: FindNodeResponse, b: FindNodeResponse)             => a === b
      case (a: NodesWithPeersResponse, b: NodesWithPeersResponse) => a === b
      case (_, _)                                                 => false
    }
    implicit val showKmessage: Show[KMessage] = Show.fromToString

    implicit val fieldName: FieldName = FieldName.snakeCaseFieldName

    final case class Ping(t: Transaction, id: NodeId) extends KMessage

    object Ping {
      implicit lazy val bencoder: BEncoder[Ping] = BCodec[Ping]

      implicit lazy val bdecoder: BDecoder[Ping] = for {
        t      <- BDecoder.at[Transaction]("t")
        nodeId <- getAField[NodeId]("id")
      } yield Ping(t, nodeId)

      implicit val eqPing: Eq[Ping] =
        Eq.instance((a, b) => a.t === b.t && a.id === b.id)
    }

    final case class FindNode(t: Transaction, id: NodeId, target: NodeId)
        extends KMessage

    object FindNode {
      implicit lazy val bencoder: BEncoder[FindNode] = BCodec[FindNode]

      implicit lazy val bdecoder: BDecoder[FindNode] = for {
        t      <- BDecoder.at[Transaction]("t")
        id     <- getAField[NodeId]("id")
        target <- getAField[NodeId]("target")
      } yield FindNode(t, id, target)

      implicit val eqFindNode: Eq[FindNode] = Eq.instance(
        (a, b) => a.t === b.t && a.id === b.id && a.target === b.target
      )
    }

    final case class GetPeers(
        t: Transaction,
        id: NodeId,
        infoHash: InfoHash
    ) extends KMessage

    object GetPeers {
      implicit lazy val bencoder: BEncoder[GetPeers] = BCodec[GetPeers]
      implicit lazy val bdecoder: BDecoder[GetPeers] = for {
        t        <- BDecoder.at[Transaction]("t")
        id       <- getAField[NodeId]("id")
        infoHash <- getAField[InfoHash]("info_hash")
      } yield GetPeers(t, id, infoHash)

      implicit val eqGetPeers: Eq[GetPeers] = Eq.instance(
        (a, b) => a.t === b.t && a.id === b.id && a.infoHash === b.infoHash
      )
    }

    @newtype final case class ImpliedPort(value: Boolean) {

      def implied[A](ifTrue: => A, ifFalse: => A): A =
        if (value)
          ifTrue
        else
          ifFalse
    }
    object ImpliedPort {
      implicit lazy val bcodec: BCodec[ImpliedPort] = BCodec.intBCodec.xmap(
        i => ImpliedPort(i > 0),
        a => if (a.value) 1 else 0
      )
      implicit val eqImpliedPort: Eq[ImpliedPort] =
        Eq.instance(_.value == _.value)
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
      implicit lazy val bencoder: BEncoder[AnnouncePeer] = BCodec[AnnouncePeer]
      implicit lazy val bdecoder: BDecoder[AnnouncePeer] = for {
        t           <- BDecoder.at[Transaction]("t")
        impliedPort <- getAField[ImpliedPort]("implied_port")
        id          <- getAField[NodeId]("id")
        infoHash    <- getAField[InfoHash]("info_hash")
        port        <- getAField[Port]("port")
        token       <- getAField[Token]("token")
      } yield AnnouncePeer(t, impliedPort, id, infoHash, port, token)

      implicit val eqAnnouncePeer: Eq[AnnouncePeer] =
        Eq.instance(
          (a, b) =>
            a.t === b.t && a.impliedPort === b.impliedPort && a.id === b.id && a.infoHash === b.infoHash && a.port === b.port && a.token === b.token
        )
    }

    final case class RpcErrorMessage(
        t: Transaction,
        e: RpcError
    ) extends KMessage

    object RpcErrorMessage {

      implicit lazy val bdecoder: BDecoder[RpcErrorMessage] =
        BCodec[RpcErrorMessage]
      implicit lazy val bencoder: BEncoder[RpcErrorMessage] =
        BCodec[RpcErrorMessage]

      implicit val eqRpcErrorMessage: Eq[RpcErrorMessage] =
        Eq.instance((a, b) => a.t === b.t && a.e === b.e)
    }

    final case class NodeIdResponse(t: Transaction, id: NodeId) extends KMessage

    object NodeIdResponse {
      implicit lazy val bencoder: BEncoder[NodeIdResponse] =
        BCodec[NodeIdResponse]

      implicit lazy val bdecoder: BDecoder[NodeIdResponse] = for {
        t  <- BDecoder.at[Transaction]("t")
        id <- getRField[NodeId]("id")
      } yield NodeIdResponse(t, id)

      implicit val eqNodeIdResponse: Eq[NodeIdResponse] = Eq.instance(
        (a, b) => a.t === b.t && a.id === b.id
      )
    }

    final case class FindNodeResponse(
        t: Transaction,
        id: NodeId,
        nodes: List[Node]
    ) extends KMessage

    object FindNodeResponse {
      implicit lazy val bencoder: BEncoder[FindNodeResponse] =
        BCodec[FindNodeResponse]
      implicit lazy val bdecoder: BDecoder[FindNodeResponse] = for {
        t     <- BDecoder.at[Transaction]("t")
        id    <- getRField[NodeId]("id")
        nodes <- getRField[List[Node]]("nodes")
      } yield FindNodeResponse(t, id, nodes)

      implicit val eqFindNodeResponse: Eq[FindNodeResponse] =
        Eq.instance { (a, b) =>
          a.t === b.t &&
          a.id === b.id &&
          a.nodes === b.nodes
        }
    }

    final case class NodesWithPeersResponse(
        t: Transaction,
        id: NodeId,
        token: Token,
        nodes: Option[List[Node]],
        values: Option[List[Peer]]
    ) extends KMessage

    object NodesWithPeersResponse {

      implicit lazy val bencoder: BEncoder[NodesWithPeersResponse] =
        BCodec[NodesWithPeersResponse]

      implicit lazy val bdecoder: BDecoder[NodesWithPeersResponse] = for {
        t     <- BDecoder.at[Transaction]("t")
        id    <- getRField[NodeId]("id")
        token <- getRField[Token]("token")
        nodes <- getRField[Option[List[Node]]]("nodes")
        peers <- getRField[Option[List[Peer]]]("values")
      } yield NodesWithPeersResponse(t, id, token, nodes, peers)

      implicit val eqNodesWithPeersResponse: Eq[NodesWithPeersResponse] =
        Eq.instance(
          (a, b) =>
            a.t === b.t && a.id === b.id && a.token === b.token && a.values === b.values
        )
    }

    implicit lazy val bdecoder: BDecoder[KMessage] = BDecoder.instance(
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
            v.as[NodesWithPeersResponse] orElse v.as[FindNodeResponse] orElse v
              .as[NodeIdResponse]

          case "e" => v.as[RpcErrorMessage]
          case m =>
            BencError.CodecError(s"$m Unsupported message type ").asLeft
        }
    )

    implicit lazy val bencoder: BEncoder[KMessage] = {

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

        case pr @ NodesWithPeersResponse(t, _, _, _, _) =>
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
    val logger = Slf4jLogger.getLogger[IO]
    def apply(
        socket: Socket[IO],
        readTimeout: Option[FiniteDuration] = None,
        outputBound: Int = 1024
    )(
        implicit c: Concurrent[IO]
    ): IO[KMessageSocket] =
      for {
        outgoing <- Queue.bounded[IO, KPacket](outputBound)
      } yield new KMessageSocket {

        override def read: Stream[IO, KPacket] = {
          val readSocket = socket
            .reads(readTimeout)
            .flatMap { packet =>
              Stream
                .chunk(packet.bytes)
                .through(StreamDecoder.many(KMessage.codec).toPipeByte[IO])
                .map((packet.remote, _))
                .evalTap {
                  case (_, m) =>
                    logger.debug(s"read: $m")
                }
            }

          val writeOutput = outgoing.dequeue
            .flatMap {
              case (remote, msg) =>
                Stream.eval_(logger.debug(s"write: $msg")) ++
                  Stream
                    .emit(msg)
                    .through(StreamEncoder.many(KMessage.codec).toPipeByte[IO])
                    .chunks
                    .map { data =>
                      Packet(remote, data)
                    }
            }
            .through(socket.writes(readTimeout))

          readSocket.concurrently(writeOutput)
        }

        override def write1(
            remote: InetSocketAddress,
            msg: KMessage
        ): IO[Unit] = outgoing.enqueue1((remote, msg))
      }

    def createSocket(
        sg: SocketGroup,
        readTimeout: Option[FiniteDuration] = None,
        port: Option[Port] = None
    )(
        implicit c: Concurrent[IO],
        cs: ContextShift[IO]
    ): Stream[IO, KMessageSocket] = {

      Stream
        .resource {
          sg.open(new InetSocketAddress(port.map(_.value).getOrElse(0)))
            .flatMap { s =>
              Resource(IO(s).map(_ -> s.close))
            }
        }
        .flatMap(socket => Stream.eval(KMessageSocket(socket, readTimeout)))

    }
  }

}
