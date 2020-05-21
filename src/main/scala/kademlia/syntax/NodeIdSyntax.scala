package kademlia.syntax

import kademlia.types.{ NodeId, Prefix }
import cats.syntax.order._

trait NodeIdSyntax {
  implicit def nodeIdSyntax(nodeId: NodeId): NodeIdOps = new NodeIdOps(nodeId)
}

final class NodeIdOps(val nodeId: NodeId) extends AnyVal {

  def ^(other: NodeId): NodeId = NodeId(nodeId.value ^ other.value)

  def closest[A](a: NodeId, b: NodeId)(ifaCloser: => A, ifbCloser: => A): A = {
    val f = nodeId ^ a
    val s = nodeId ^ b
    if (f < s) ifaCloser else ifbCloser
  }
}

trait PrefixSyntax {
  implicit def prefixSyntax(prefix: Prefix): PrefixOps = new PrefixOps(prefix)
}

final class PrefixOps(val prefix: Prefix) extends AnyVal {
  def toNodeId: NodeId = NodeId(prefix.value)
}
