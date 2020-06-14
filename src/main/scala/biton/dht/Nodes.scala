package biton.dht

import java.time.Clock

import biton.dht.types.{ KSize, Node, NodeActivity }
import cats.Eq
import cats.instances.order._
import cats.syntax.eq._

import scala.annotation.tailrec
final case class Nodes(value: Vector[NodeActivity], ksize: KSize)
    extends Product
    with Serializable {

  def filterNot(node: Node): Nodes =
    Nodes(value.filterNot(_.node.nodeId === node.nodeId), ksize)

  def get(i: Int): NodeActivity = value(i)
  def bad: Vector[NodeActivity] =
    value.filter(_.count.value > 0).sortBy(_.count.inc)

  def swap(node: Node, replacement: Node)(implicit clock: Clock): Nodes =
    find(node).fold(this) {
      case (_, i) =>
        Nodes(value.updated(i, NodeActivity(replacement)), ksize)
    }

  def find(node: Node): Option[(NodeActivity, Int)] =
    value.zipWithIndex.find(_._1.node.nodeId === node.nodeId)

  def failOne(node: Node): Nodes = find(node).fold(this) {
    case (NodeActivity(node, lastActive, count), i) =>
      Nodes(value.updated(i, NodeActivity(node, lastActive, count.inc)), ksize)
  }

  @tailrec
  def fail(node: Node*): Nodes =
    node match {
      case Seq()           => this
      case Seq(x)          => failOne(x)
      case Seq(x, xs @ _*) => failOne(x).fail(xs: _*)
    }

  def append(node: Node)(implicit clock: Clock): Result[Nodes] = {
    Either.cond(
      nonFull,
      Nodes(value :+ NodeActivity(node), ksize),
      Error.KBucketError(s"Bucket is full for Node $node")
    )
  }

  def exists(node: Node): Boolean   = value.exists(_.node.nodeId === node.nodeId)
  def nonExist(node: Node): Boolean = !exists(node)
  def isFull: Boolean               = value.size == ksize.value
  def nonFull: Boolean              = !isFull
  def isEmpty: Boolean              = value.isEmpty
  def nonEmpty: Boolean             = !isEmpty
}

object Nodes {
  implicit val nodesEq2: Eq[Nodes] = Eq.fromUniversalEquals
}
