package biton.dht

import java.time.{ Clock, LocalDateTime }

import biton.dht.KBucket._
import biton.dht.types._
import cats.{ Apply, Eq }
import cats.implicits._

sealed abstract class KBucket extends Product with Serializable {
  def from: Prefix
  def to: Prefix
  def nodes: Nodes
  def lastUpdated: LocalDateTime

  def isFull: Boolean = this match {
    case KBucket.EmptyBucket(_, _, _, _) => false
    case KBucket.Bucket(_, _, _, _)      => false
    case KBucket.FullBucket(_, _, _, _)  => true
  }

  def questionable: List[Node] = ???

  def findBad: Option[Node] = nodes.bad.headOption.map(_.node)

  def replace(node: Node, replacement: Node)(
      implicit clock: Clock
  ): Result[KBucket] =
    KBucket.create(from, to, nodes.replace(node, replacement))

  def inRange(nodeId: NodeId): Boolean = {
    val id = nodeId.toPrefix
    id >= from && id < to
  }

  def canSplit: Boolean = from < to

  def isNewNode(node: Node): Boolean =
    nodes.nonExist(node)

  def nonNewNode(node: Node): Boolean = !isNewNode(node)

  def midpoint: Result[Prefix] = {
    Either.cond(
      canSplit,
      (from + to) / 2,
      Error.KBucketError(
        s"Not able to create next prefix. Already the last one - $from "
      )
    )
  }

  def split()(implicit clock: Clock): Result[(KBucket, KBucket)] = this match {
    case b @ KBucket.EmptyBucket(_, _, _, _) =>
      Error.KBucketError(s"Can not split empty bucket $b").asLeft
    case b @ KBucket.Bucket(_, _, _, _) =>
      Error.KBucketError(s"Can not split not full  bucket $b").asLeft

    case KBucket.FullBucket(
        from,
        to,
        Nodes(nodes, ksize),
        _
        ) =>
      val nodesPair = (Vector.empty[NodeActivity], Vector.empty[NodeActivity])

      def splitNodes(mid: Prefix, _nodes: Vector[NodeActivity]) =
        _nodes.foldLeft(nodesPair) {
          case ((f, s), n) =>
            val id = n.node.nodeId.toPrefix
            if (id >= from && id < mid) (n +: f, s)
            else (f, n +: s)
        }

      for {
        mid <- midpoint
        (f, s) = splitNodes(mid, nodes)
        first <- KBucket
          .create(from, mid, Nodes(f, ksize))
        second <- KBucket
          .create(mid, to, Nodes(s, ksize))
      } yield (first, second)
  }

  def fail(node: Node*)(implicit clock: Clock): Result[KBucket] =
    KBucket.create(from, to, nodes.fail(node: _*), lastUpdated)

  def remove(node: Node)(implicit clock: Clock): Result[KBucket] = {
    KBucket.create(from, to, nodes.filterNot(node))
  }

  def find(nodeId: NodeId): Option[Node] =
    nodes.value.find(_.node.nodeId === nodeId).map(_.node)

  def add(node: Node*)(implicit clock: Clock): Result[KBucket] = {
    node match {
      case Seq()   => this.asRight
      case x :: xs => addOne(x).flatMap(_.add(xs: _*))

    }
  }

  def addOne(node: Node)(implicit clock: Clock): Result[KBucket] = {
    assert(inRange(node.nodeId))
    this match {
      case KBucket.EmptyBucket(from, to, nodes, _) =>
        for {
          n <- nodes.append(node)
          b <- KBucket.create(from, to, n)
        } yield b

      case KBucket.Bucket(from, to, nodes, _) =>
        for {
          n <- nodes.filterNot(node).append(node)
          b <- KBucket.create(from, to, n)
        } yield b

      case KBucket.FullBucket(from, to, nodes, _) if nonNewNode(node) =>
        for {
          n <- nodes.filterNot(node).append(node)
          b <- KBucket.create(from, to, n)
        } yield b
      case b @ KBucket.FullBucket(_, _, __, _) =>
        Error.KBucketError(s"$b is full").asLeft[KBucket]
    }
  }
}

object KBucket {

  final case class EmptyBucket(
      from: Prefix,
      to: Prefix,
      nodes: Nodes,
      lastUpdated: LocalDateTime
  ) extends KBucket

  final case class FullBucket(
      from: Prefix,
      to: Prefix,
      nodes: Nodes,
      lastUpdated: LocalDateTime
  ) extends KBucket

  final case class Bucket(
      from: Prefix,
      to: Prefix,
      nodes: Nodes,
      lastUpdated: LocalDateTime
  ) extends KBucket

  def create(
      from: Prefix,
      to: Prefix,
      nodes: Nodes,
      lastUpdated: LocalDateTime
  )(implicit clock: Clock): Result[KBucket] = {
    assert(from < to, "'From' should be less than 'to'")
    if (nodes.isFull)
      FullBucket(from, to, nodes, lastUpdated).asRight
    else if (nodes.isEmpty)
      EmptyBucket(from, to, nodes, lastUpdated).asRight
    else
      Bucket(from, to, nodes, lastUpdated).asRight
  }
  def create(
      from: Prefix,
      to: Prefix,
      nodes: Nodes
  )(implicit clock: Clock): Result[KBucket] = {
    create(from, to, nodes, LocalDateTime.now(clock))

  }

  implicit val kbucketEq: Eq[KBucket] = Eq.fromUniversalEquals
}
