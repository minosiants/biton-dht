package biton.dht

import java.time.{ Clock, LocalDateTime }

import biton.dht.types._
import cats.Eq
import cats.implicits._

/**
  * Bucket that holds up to ksize of nodes in range of `from` - `to`
  *
  */
sealed abstract class KBucket extends Product with Serializable {

  /**
    * [[Prefix]] from which this bucket holds nodes
    * Value is inclusive
    */
  def from: Prefix

  /**
    * [[Prefix]] to which this bucket holds nodes
    * Value is exclusive
    */
  def to: Prefix

  /**
    * [[Nodes]] that holds all bucket's nodes
    *
    */
  def nodes: Nodes

  /**
    * Last time when this bucket was updated
    */
  def lastUpdated: LocalDateTime

  /**
    * Checks if the bucket is full
    */
  def isFull: Boolean = this match {
    case KBucket.EmptyBucket(_, _, _, _) => false
    case KBucket.Bucket(_, _, _, _)      => false
    case KBucket.FullBucket(_, _, _, _)  => true
  }

  /**
    * Returns random node from this bucket
    */
  def random: Node = {
    assert(nodes.nonEmpty)
    nodes.get(Random.rint(nodes.value.size)).node
  }

  /**
    * Returns nodes that was added/updated before provided time
    */
  def outdatedNodes(time: LocalDateTime): Vector[Node] = {
    nodes.value.collect {
      case NodeActivity(node, lastActive, _) if lastActive.isBefore(time) =>
        node
    }
  }

  /**
    * Finds node which at least once refused to respond
    */
  def findBad: Option[Node] = nodes.bad.headOption.map(_.node)

  /**
    * Swap `node` in this bucket onto `replacement`
    */
  def swap(node: Node, replacement: Node)(
      implicit clock: Clock
  ): Result[KBucket] =
    KBucket.create(from, to, nodes.swap(node, replacement))

  /**
    * Checks if `nodeId` is in this bucket range
    */
  def inRange(nodeId: NodeId): Boolean = {
    val id = nodeId.toPrefix
    id >= from && id < to
  }

  /**
    * Checks if this bucket can be split
    */
  def canSplit: Boolean = from < to

  /**
    * Check if this bucket does not have provided `node`
    */
  def isNewNode(node: Node): Boolean =
    nodes.nonExist(node)

  /**
    * Checks if `node` is already in the bucket
    */
  def nonNewNode(node: Node): Boolean = !isNewNode(node)

  /**
    * Create a splitting prefix
    */
  def midpoint: Result[Prefix] = {
    Either.cond(
      canSplit,
      (from + to) / 2,
      Error.KBucketError(
        s"Not able to create next prefix. Already the last one - $from "
      )
    )
  }

  /**
    * Split this bucket into two buckets
    */
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

  /**
    * Mark nodes as bad
    */
  def fail(node: Node*): Result[KBucket] =
    KBucket.create(from, to, nodes.fail(node: _*), lastUpdated)

  /**
    * Remove nodes from this bucket
    */
  def remove(node: Node)(implicit clock: Clock): Result[KBucket] = {
    KBucket.create(from, to, nodes.filterNot(node))
  }

  /**
    * Find a `node`` by provided `nodeId`
    */
  def find(nodeId: NodeId): Option[Node] =
    nodes.value.find(_.node.nodeId === nodeId).map(_.node)

  /**
    * Add nodes to this bucket
    */
  def add(node: Node*)(implicit clock: Clock): Result[KBucket] = {
    node match {
      case Seq()           => this.asRight
      case Seq(x)          => addOne(x)
      case Seq(x, xs @ _*) => addOne(x).flatMap(_.add(xs: _*))
    }
  }

  /**
    * Add one node this bucket
    */
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
      case b @ KBucket.FullBucket(_, _, _, _) =>
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

  /**
    * Smart constructor that creates [[KBucket]] with provided last update time
    */
  def create(
      from: Prefix,
      to: Prefix,
      nodes: Nodes,
      lastUpdated: LocalDateTime
  ): Result[KBucket] = {
    assert(from < to, "'From' should be less than 'to'")
    if (nodes.isFull)
      FullBucket(from, to, nodes, lastUpdated).asRight
    else if (nodes.isEmpty)
      EmptyBucket(from, to, nodes, lastUpdated).asRight
    else
      Bucket(from, to, nodes, lastUpdated).asRight
  }

  /**
    * Smart constructor that creates [[KBucket]] with current time
    */
  def create(
      from: Prefix,
      to: Prefix,
      nodes: Nodes
  )(implicit clock: Clock): Result[KBucket] = {
    create(from, to, nodes, LocalDateTime.now(clock))

  }

  implicit val kbucketEq: Eq[KBucket] = Eq.fromUniversalEquals
}
