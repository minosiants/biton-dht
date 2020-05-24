package kademlia.syntax

import cats.syntax.order._
import kademlia.types.NodeId
import scodec.codecs._

trait NodeIdSyntax {
  implicit def nodeIdSyntax(nodeId: NodeId): NodeIdOps = new NodeIdOps(nodeId)
}

final class NodeIdOps(val nodeId: NodeId) extends AnyVal {

  def ^(other: NodeId): NodeId = NodeId(nodeId.value ^ other.value)
  def >>>(n: Int): NodeId      = NodeId(nodeId.value >>> n)
  def closest[A](a: NodeId, b: NodeId)(ifaCloser: => A, ifbCloser: => A): A = {
    val f = nodeId ^ a
    val s = nodeId ^ b
    if (f < s) ifaCloser else ifbCloser
  }
  def toDecStr: String = {
    val d = sizedList(4, uint32)
    val result = d
      .decodeValue(nodeId.value)
      .map(_.underlying.toList.mkString(""))
      .toOption
      .getOrElse("")
    result.reverse.padTo(40, '0').reverse
  }

}
