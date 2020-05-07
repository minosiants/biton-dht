package kademlia

import java.time.LocalDateTime

import cats.effect.specs2.CatsIO
import com.comcast.ip4s.IpAddress
import org.scalacheck.Gen
import org.specs2.ScalaCheck
import org.specs2.mutable.Specification
import types._

class KSpec extends Specification with ScalaCheck with CatsIO {

  val nodeIdIntGen: Gen[NodeId] = Gen.posNum[Int].map(NodeId.fromInt)

  val ipV4Gen: Gen[IpAddress] = for {
    one   <- Gen.posNum[Byte]
    two   <- Gen.posNum[Byte]
    three <- Gen.posNum[Byte]
    four  <- Gen.posNum[Byte]
  } yield IpAddress.fromBytes(Array(one, two, three, four)).get

  val nodeGen: Gen[Node] = for {
    id   <- nodeIdIntGen
    ip   <- ipV4Gen
    port <- Gen.posNum[Int]
  } yield Node(id, ip, port, LocalDateTime.now)

  def listOfNodesGen(size: Int): Gen[List[Node]] =
    Gen
      .infiniteStream(nodeGen)
      .map(_.take(size * 4).toSet.take(size).toList)
      .retryUntil(_.size == size)

}
