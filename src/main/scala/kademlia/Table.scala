package kademlia

import java.time.Clock

import cats.Show
import cats.data.NonEmptyVector
import cats.instances.int._
import cats.syntax.either._
import cats.syntax.foldable._
import cats.syntax.eq._
import cats.syntax.show._
import kademlia.KBucket.{ Cache, FullBucket }
import kademlia.TraversalNode.{ Fresh, Responded, Stale }
import kademlia.TraversalTable.{ Completed, InProgress }
import kademlia.protocol.InfoHash
import kademlia.types._

import scala.Function._
import scala.annotation.tailrec

trait Table extends Product with Serializable {
  def nodeId: NodeId
  def bsize: Long = kbuckets.size
  def size: Long  = kbuckets.map(_.nodes.value.size).fold
  def kbuckets: NonEmptyVector[KBucket]
  def addNode(node: Node): Result[Table]
  def addNodes(nodes: List[Node]): Result[Table]
  def neighbors(nodeId: NodeId): List[Node]
  def markNodeAsBad(node: Node): Result[Table]
  def markNodesAsBad(node: List[Node]): Result[Table]
}

object Table {

  type IndexKBucket = (KBucket, Index)

  def empty(
      nodeId: NodeId,
      ksize: KSize = KSize(8),
      lowerPrefix: Prefix = Prefix(0),
      higherPrefix: Prefix = Prefix(BigInt(2).pow(160))
  )(implicit clock: Clock): Result[Table] = {

    val nodes = Nodes(List.empty, ksize)
    val cache = Cache(Nodes(List.empty, ksize * 3))
    for {
      b <- KBucket.create(lowerPrefix, higherPrefix, nodes, cache)
    } yield KTable(nodeId, NonEmptyVector.of(b))

  }
}

final case class KTable(
    nodeId: NodeId,
    kbuckets: NonEmptyVector[KBucket]
)(implicit val c: Clock)
    extends Table {

  def findBucketFor(
      id: NodeId
  ): Result[Table.IndexKBucket] = {
    kbuckets.zipWithIndex
      .find { case (kb, _) => kb.inRange(id) }
      .map { case (kb, i) => (kb, Index(i)) }
      .toRight(Error.KBucketError(s"bucket for $nodeId not found"))
  }
  def addNodeToBucket(
      node: Node,
      bucket: KBucket
  ): Result[NonEmptyVector[KBucket]] = {

    (bucket.canSplit, bucket.inRange(nodeId), bucket) match {
      case (true, true, b @ FullBucket(_, _, _, _, _)) =>
        b.split().flatMap {
          case (first, second) =>
            if (first.inRange(node.nodeId))
              (first.add(node) orElse first
                .addToCache(node)) map (NonEmptyVector.of(_, second))
            else
              (second.add(node) orElse second
                .addToCache(node)) map (NonEmptyVector.of(first, _))
        }
      case (_, false, b @ FullBucket(_, _, _, _, _)) =>
        b.addToCacheIfNew(node) map (NonEmptyVector.of(_))

      case (_, _, bucket) =>
        (bucket.add(node) orElse bucket.addToCache(node))
          .map(NonEmptyVector.one)
    }

  }

  def insertBuckets(
      index: Index,
      buckets: NonEmptyVector[KBucket]
  ): NonEmptyVector[KBucket] = {
    val vec = kbuckets.toVector
    val newVec = vec.take(index.value) ++ buckets.toVector ++ vec.drop(
      index.value + 1
    )
    NonEmptyVector(newVec.head, newVec.drop(1))
  }

  override def addNode(node: Node): Result[Table] = {

    for {
      (kb, i) <- findBucketFor(node.nodeId)
      list    <- addNodeToBucket(node, kb)
      _   = list.toVector.head.nodes
      res = insertBuckets(i, list.reverse)
    } yield KTable(nodeId, res)
  }

  override def addNodes(nodes: List[Node]): Result[Table] = {
    @tailrec
    def go(t: Result[Table], _nodes: List[Node]): Result[Table] = {
      (t, _nodes) match {
        case (e @ Left(_), _) => e
        case (_, Nil)         => t
        case (_, x :: xs) =>
          go(t.flatMap(_.addNode(x)), xs)
      }
    }
    go(this.asRight, nodes)
  }

  override def neighbors(nodeId: NodeId): List[Node] = {
    assert(kbuckets.nonEmpty)
    kbuckets.filter(_.inRange(nodeId)).head.nodes.value
  }

  override def markNodeAsBad(node: Node): Result[Table] = {
    for {
      (kb, i) <- findBucketFor(node.nodeId)
      kb1     <- kb.replaceFromCache(node)
      updated = insertBuckets(i, NonEmptyVector.of(kb1))
    } yield KTable(nodeId, updated)
  }

  override def markNodesAsBad(node: List[Node]): Result[Table] = {
    node match {
      case Nil     => this.asRight[Error]
      case x :: xs => markNodeAsBad(x).flatMap(_.markNodesAsBad(xs))
    }
  }
}

sealed abstract class TraversalNode {
  def distance: Distance
  def node: Node
  def fold[A](
      fresh: (Node, Distance) => A,
      stale: (Node, Distance) => A,
      responded: (Node, NodeInfo, Distance) => A
  ): A = this match {
    case Fresh(node, distance)           => fresh(node, distance)
    case Stale(node, distance)           => stale(node, distance)
    case Responded(node, info, distance) => responded(node, info, distance)
  }
  def isFresh: Boolean =
    fold((_, _) => true, (_, _) => false, (_, _, _) => false)
  def nonFresh: Boolean = !isFresh
  def isStale: Boolean =
    fold((_, _) => false, (_, _) => true, (_, _, _) => false)
  def nonStale: Boolean = !isStale
  def isResponded: Boolean =
    fold((_, _) => false, (_, _) => false, (_, _, _) => true)
  def nonResponded: Boolean = !isResponded

}
object TraversalNode {
  final case class Fresh(node: Node, distance: Distance) extends TraversalNode
  final case class Stale(node: Node, distance: Distance) extends TraversalNode
  final case class Responded(node: Node, info: NodeInfo, distance: Distance)
      extends TraversalNode

  implicit val showTraversalNode: Show[TraversalNode] = Show.show {
    case Fresh(node, distance) =>
      s"Fresh:     ${node.nodeId.toBigInt} distance: $distance"
    case Stale(node, distance) =>
      s"Stale:     ${node.nodeId.toBigInt} distance: $distance"
    case Responded(node, _, distance) =>
      s"Responded: ${node.nodeId.toBigInt} distance: $distance"
  }
}

sealed abstract class TraversalTable {
  def target: NodeId
  def nodes: List[TraversalNode]
  def completeSize: Int

  def markNodesAsStale(n: List[Node]): TraversalTable = {
    n match {
      case Nil     => this
      case x :: xs => markNodeAsStale(x).markNodesAsStale(xs)
    }
  }

  def markNodeAsStale(n: Node): TraversalTable = {
    updateNode(n) { x =>
      Stale(n, x.distance)
    }
  }
  def markNodesAsResponded(n: List[NodeInfo]): TraversalTable = {
    n match {
      case Nil     => this
      case x :: xs => markNodeAsResponded(x).markNodesAsResponded(xs)
    }
  }
  def markNodeAsResponded(n: NodeInfo): TraversalTable = {
    updateNode(n.node) { x =>
      Responded(n.node, n, x.distance)
    }
  }

  def topFresh(n: Int): List[Node] =
    nodes.collect { case Fresh(node, _) => node }.take(n)

  def topResponded(n: Int): List[NodeInfo] =
    nodes
      .collect {
        case r @ Responded(_, info, _) => info
      }
      .take(n)

  def updateNode(n: Node)(f: TraversalNode => TraversalNode): TraversalTable = {
    def isComplete(l: List[TraversalNode]): Boolean = {
      l.takeWhile(_.nonFresh).count(_.isResponded) >= completeSize
    }

    nodes.zipWithIndex.find { case (el, _) => el.node.nodeId === n.nodeId } match {
      case None => this
      case Some((node, i)) =>
        this match {
          case Completed(target, _, s) =>
            Completed(target, nodes.updated(i, f(node)), s)
          case TraversalTable.InProgress(target, _, s) =>
            val _nodes = nodes.updated(i, f(node))
            if (isComplete(_nodes))
              Completed(target, _nodes, s)
            else
              InProgress(target, _nodes, s)
        }
    }
  }

}

object TraversalTable {
  final case class InProgress(
      target: NodeId,
      nodes: List[TraversalNode],
      completeSize: Int
  ) extends TraversalTable {
    def addNodes(n: List[Node]): TraversalTable = {
      val nodesToAdd =
        n.distinct.filterNot(v => nodes.exists(_.node.nodeId === v.nodeId))
      val result = (nodesToAdd.map(
        v => Fresh(v, v.nodeId.distance(target))
      ) ++ nodes).sortBy(_.distance)
      TraversalTable.InProgress(target, result, completeSize)
    }

  }

  final case class Completed(
      target: NodeId,
      nodes: List[TraversalNode],
      completeSize: Int
  ) extends TraversalTable

  def create(
      infoHash: InfoHash,
      nodes: List[Node],
      completeSize: Int
  ): TraversalTable =
    InProgress(NodeId(infoHash.value), Nil, completeSize).addNodes(nodes)

  def log(l: List[TraversalNode]): String =
    s"""\n ${l.map(_.show).mkString("\n")}"""
}
