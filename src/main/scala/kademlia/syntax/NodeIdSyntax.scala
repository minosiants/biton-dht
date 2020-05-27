package kademlia.syntax

import kademlia.types.{ Distance, NodeId, Prefix }

trait NodeIdSyntax {
  implicit def nodeIdSyntax(nodeId: NodeId): NodeIdOps = new NodeIdOps(nodeId)
}

final class NodeIdOps(val nodeId: NodeId) extends AnyVal {
  def toPrefix: Prefix         = Prefix(BigInt(1, nodeId.value.toByteArray))
  def ^(other: NodeId): NodeId = NodeId(nodeId.value ^ other.value)
  def distance(other: NodeId): Distance =
    Distance(
      BigInt(1, nodeId.value.toByteArray) ^ BigInt(1, other.value.toByteArray)
    )

}
