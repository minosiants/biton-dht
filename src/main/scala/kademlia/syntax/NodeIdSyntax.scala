package kademlia.syntax

import kademlia.types.{NodeId, Prefix}

trait NodeIdSyntax {
  implicit def nodeIdSyntax(nodeId:NodeId): NodeIdOps = new NodeIdOps(nodeId)
}

final class NodeIdOps(val nodeId:NodeId) extends AnyVal {
  def ^ (other:NodeId): NodeId = NodeId(nodeId.value ^ other.value)
}

trait PrefixSyntax {
  implicit def prefixSyntax(prefix:Prefix):PrefixOps = new PrefixOps(prefix)
}

final class PrefixOps (val prefix:Prefix) extends AnyVal {
  def toNodeId: NodeId = NodeId(prefix.value)
}

