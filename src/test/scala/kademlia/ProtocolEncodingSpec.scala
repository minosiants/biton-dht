package kademlia
import benc.{ BCodec, BDecoder, BEncoder, BencError }
import cats.Eq
import kademlia.protocol.KMessage._
import org.scalacheck.Prop._
import org.scalacheck._
import protocol._
import cats.syntax.either._
import kademlia.types.NodeId
import cats.syntax.show._

class ProtocolEncodingSpec extends KSuite {

  // property("Ping")(prop[Ping])
  //property("FindNode")(prop[FindNode])
  //property("GetPeers")(prop[GetPeers])
  //property("AnnouncePeer")(prop[AnnouncePeer])
  //property("RpcErrorMessage")(prop[RpcErrorMessage])
  //property("PingResponse")(prop[PingResponse])
  property("FindNodeResponse")(prop[FindNodeResponse])
  /*  property("RpcError")(prop[RpcError])
    property("GetPeersNodesResponse")(prop[GetPeersNodesResponse])
    property("GetPeersResponse")(prop[GetPeersResponse])*/

  def prop[A <: KMessage: BEncoder: BDecoder: Gen: Eq]: Prop = {
    val gen = implicitly[Gen[A]]
    forAll(gen) { a =>
      val encoded = BEncoder[KMessage].encode(a)
      println(encoded)
      val decoded = encoded.flatMap(BDecoder[KMessage].decode)
      println(decoded)
      println(a)
      decoded.leftMap { e: BencError =>
        println(e.show)
      }
      decoded === a.asRight
    }
  }

  val rpcErrorCodeGen: Gen[RpcErrorCode] =
    Gen.oneOf(201, 202, 203, 204).map(RpcErrorCode.find(_).get)

  val infoHashGen: Gen[InfoHash] = bitVectorGen(idLength).map(InfoHash(_))

  val tokenGen: Gen[Token] = bitVectorGen(2 * 8).map(Token(_))

  val peerGen: Gen[Peer] = for {
    ip   <- ipV4Gen
    port <- portGen
  } yield Peer(ip, port)

  val transactionGen: Gen[Transaction] = Gen.alphaStr.map(Transaction(_))

  implicit val rpcErrorGen: Gen[RpcError] = for {
    code <- rpcErrorCodeGen
    msg  <- Gen.alphaStr
  } yield RpcError(code, msg)

  implicit val pingGen: Gen[Ping] = for {
    t      <- transactionGen
    nodeId <- nodeIdIntGen
  } yield Ping(t, nodeId)

  implicit val findNodeGen: Gen[FindNode] = for {
    t      <- transactionGen
    nodeId <- nodeIdIntGen
    target <- nodeIdIntGen
  } yield FindNode(t, nodeId, target)

  implicit val getPeersGen: Gen[GetPeers] = for {
    t        <- transactionGen
    nodeId   <- nodeIdIntGen
    infoHash <- infoHashGen
  } yield GetPeers(t, nodeId, infoHash)

  implicit val announcePeerGen: Gen[AnnouncePeer] = for {
    t        <- transactionGen
    ip       <- Gen.oneOf(true, false).map(ImpliedPort(_))
    id       <- nodeIdIntGen
    infoHash <- infoHashGen
    port     <- portGen
    token    <- tokenGen
  } yield AnnouncePeer(t, ip, id, infoHash, port, token)

  implicit val rpcErrorMessage: Gen[RpcErrorMessage] = for {
    t     <- transactionGen
    error <- rpcErrorGen
  } yield RpcErrorMessage(t, error)

  implicit val pingResponseGen: Gen[PingResponse] = for {
    t      <- transactionGen
    nodeId <- nodeIdIntGen
  } yield PingResponse(t, nodeId)

  implicit val findNodeResponse: Gen[FindNodeResponse] = for {
    t      <- transactionGen
    nodeId <- nodeIdIntGen
    nodes  <- Gen.nonEmptyListOf(nodeGen)
  } yield FindNodeResponse(t, nodeId, nodes)

  implicit val getPeerNodesResponse: Gen[GetPeersNodesResponse] = for {
    t      <- transactionGen
    nodeId <- nodeIdIntGen
    token  <- tokenGen
    nodes  <- Gen.nonEmptyListOf(nodeGen)
  } yield GetPeersNodesResponse(t, nodeId, token, nodes)

  implicit val getPeersResponse: Gen[GetPeersResponse] = for {
    t      <- transactionGen
    nodeId <- nodeIdIntGen
    token  <- tokenGen
    peers  <- Gen.nonEmptyListOf(peerGen)
  } yield GetPeersResponse(t, nodeId, token, peers)

}
