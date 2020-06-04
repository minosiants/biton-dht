package biton.dht

import cats.Eq
import types.{ KSize, Node }

final case class Nodes(value: List[Node], ksize: KSize)
    extends Product
    with Serializable {

  def filterNot(node: Node): Nodes =
    Nodes(value.filterNot(_.nodeId.value == node.nodeId.value), ksize)

  def append(node: Node): Result[Nodes] = {
    Either.cond(
      nonFull,
      Nodes(value :+ node, ksize),
      Error.KBucketError(s"Bucket is full for Node $node")
    )
  }

  def prepend(node: Node): Result[Nodes] = {
    Either.cond(
      nonFull,
      Nodes(node :: value, ksize),
      Error.KBucketError(s"Bucket is full for Node $node")
    )
  }

  def dropAndPrepended(node: Node): Nodes = {
    val list: List[Node] =
      if (isFull)
        value.dropRight(1).prepended(node)
      else value.prepended(node)
    Nodes(list, ksize)
  }

  def exists(node: Node): Boolean   = value.exists(_.nodeId == node.nodeId)
  def nonExist(node: Node): Boolean = !exists(node)
  def isFull: Boolean               = value.size == ksize.value
  def nonFull: Boolean              = !isFull
  def isEmpty: Boolean              = value.isEmpty
  def nonEmpty: Boolean             = !isEmpty
}

object Nodes {
  implicit val nodesEq2: Eq[Nodes] = Eq.fromUniversalEquals
}
