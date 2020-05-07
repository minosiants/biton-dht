package kademlia

import kademlia.types.Node

final case class Nodes(value: List[Node], size: Int)
    extends Product
    with Serializable {

  def filterNot(node: Node): Nodes =
    Nodes(value.filterNot(_.nodeId.value == node.nodeId.value), size)

  def append(node: Node): Result[Nodes] = {
    Either.cond(
      nonFull,
      Nodes(value :+ node, size),
      Error.KBucketError(s"Bucket is full for Node $node")
    )
  }

  def prepend(node: Node): Result[Nodes] = {
    Either.cond(
      nonFull,
      Nodes(node :: value, size),
      Error.KBucketError(s"Bucket is full for Node $node")
    )
  }

  def dropAndPrepended(node: Node): Nodes = {
    val list: List[Node] =
      if (isFull)
        value.dropRight(1).prepended(node)
      else value.prepended(node)
    Nodes(list, size)
  }

  def isFull: Boolean   = value.size == size
  def nonFull: Boolean  = !isFull
  def isEmpty: Boolean  = value.isEmpty
  def nonEmpty: Boolean = !isEmpty
}
