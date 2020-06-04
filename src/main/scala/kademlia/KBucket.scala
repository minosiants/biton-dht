package kademlia

import java.time.{ Clock, LocalDateTime }

import cats.{ Apply, Eq }
import cats.implicits._
import kademlia.KBucket.Cache
import kademlia.types._

sealed abstract class KBucket extends Product with Serializable {
  def from: Prefix
  def to: Prefix
  def nodes: Nodes
  def cache: Cache
  def lastUpdated: LocalDateTime

  def isFull: Boolean = this match {
    case KBucket.EmptyBucket(_, _, _, _, _) => false
    case KBucket.Bucket(_, _, _, _, _)      => false
    case KBucket.FullBucket(_, _, _, _, _)  => true
  }

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
    case b @ KBucket.EmptyBucket(_, _, _, _, _) =>
      Error.KBucketError(s"Can not split empty bucket $b").asLeft
    case b @ KBucket.Bucket(_, _, _, _, _) =>
      Error.KBucketError(s"Can not split not full  bucket $b").asLeft
    case KBucket.FullBucket(
        from,
        to,
        Nodes(nodes, ksize),
        Cache(Nodes(cache, cksize)),
        _
        ) =>
      val nodesPair = (List.empty[Node], List.empty[Node])

      def splitNodes(mid: Prefix, _nodes: List[Node]) =
        _nodes.foldLeft(nodesPair) {
          case ((f, s), n) =>
            val id = n.nodeId.toPrefix
            if (id >= from && id < mid) (n :: f, s)
            else (f, n :: s)
        }

      for {
        mid <- midpoint
        (f, s)   = splitNodes(mid, nodes)
        (cf, cs) = splitNodes(mid, cache)
        first <- KBucket
          .create(from, mid, Nodes(f, ksize), Cache(Nodes(cf, cksize)))
        second <- KBucket
          .create(mid, to, Nodes(s, ksize), Cache(Nodes(cs, cksize)))
      } yield (first, second)
  }

  def addToCache(node: Node)(implicit clock: Clock): Result[KBucket] = {
    val newCache = Cache(cache.value.filterNot(node).dropAndPrepended(node))
    KBucket.create(from, to, nodes, newCache)
  }
  def addToCacheIfNew(node: Node)(implicit clock: Clock): Result[KBucket] = {
    if (isNewNode(node)) addToCache(node) else this.asRight
  }

  def remove(node: Node)(implicit clock: Clock): Result[KBucket] = {
    KBucket.create(from, to, nodes.filterNot(node), cache)
  }
  def removeFromCache(node: Node)(implicit clock: Clock): Result[KBucket] = {
    KBucket.create(from, to, nodes, cache.filterNot(node))
  }

  def find(nodeId: NodeId): Option[Node] = nodes.value.find(_.nodeId === nodeId)

  def replaceFromCache(node: Node)(implicit clock: Clock): Result[KBucket] = {
    Apply[Option]
      .map2(find(node.nodeId), cache.headOption) { (n, cn) =>
        for {
          kb     <- remove(n)
          kb2    <- kb.removeFromCache(cn)
          result <- kb2.add(cn)
        } yield result
      }
      .getOrElse(this.asRight)
  }

  def add(node: Node)(implicit clock: Clock): Result[KBucket] = {
    assert(inRange(node.nodeId))
    this match {
      case KBucket.EmptyBucket(from, to, nodes, cache, _) =>
        for {
          n <- nodes.append(node)
          b <- KBucket.create(from, to, n, cache)
        } yield b

      case KBucket.Bucket(from, to, nodes, cache, _) =>
        for {
          n <- nodes.filterNot(node).append(node)
          c = cache.filterNot(node)
          b <- KBucket.create(from, to, n, c)
        } yield b

      case b @ KBucket.FullBucket(from, to, nodes, cache, _)
          if nonNewNode(node) =>
        for {
          n <- nodes.filterNot(node).append(node)
          b <- KBucket.create(from, to, n, cache)
        } yield b
      case b @ KBucket.FullBucket(_, _, _, _, _) =>
        Error.KBucketError(s"$b is full").asLeft[KBucket]
    }
  }
}

object KBucket {

  final case class Cache(value: Nodes) extends Product with Serializable {

    def filterNot(node: Node): Cache = Cache(value.filterNot(node))
    def isEmpty: Boolean             = value.isEmpty
    def nonEmpty: Boolean            = value.nonEmpty
    def take(n: Int): Cache          = Cache(Nodes(value.value.take(n), value.ksize))
    def drop(n: Int): Cache          = Cache(Nodes(value.value.drop(n), value.ksize))
    def headOption: Option[Node]     = value.value.headOption
  }

  object Cache {
    implicit val cacheEq: Eq[Cache] = Eq.fromUniversalEquals
  }

  final case class EmptyBucket(
      from: Prefix,
      to: Prefix,
      nodes: Nodes,
      cache: Cache,
      lastUpdated: LocalDateTime
  ) extends KBucket

  final case class FullBucket(
      from: Prefix,
      to: Prefix,
      nodes: Nodes,
      cache: Cache,
      lastUpdated: LocalDateTime
  ) extends KBucket

  final case class Bucket(
      from: Prefix,
      to: Prefix,
      nodes: Nodes,
      cache: Cache,
      lastUpdated: LocalDateTime
  ) extends KBucket

  //add more validation
  def create(
      from: Prefix,
      to: Prefix,
      nodes: Nodes,
      cache: Cache
  )(implicit clock: Clock): Result[KBucket] = {
    assert(from < to, "'From' should be less than 'to'")
    if (nodes.isFull)
      FullBucket(from, to, nodes, cache, LocalDateTime.now(clock)).asRight
    else if (nodes.isEmpty && cache.nonEmpty) {
      val nodesFromCache =
        cache.take(nodes.ksize.value - 1).value.copy(ksize = nodes.ksize)
      Bucket(
        from,
        to,
        nodesFromCache,
        cache.drop(nodes.ksize.value - 1),
        LocalDateTime.now(clock)
      ).asRight

    } else if (nodes.isEmpty)
      EmptyBucket(from, to, nodes, cache, LocalDateTime.now(clock)).asRight
    else
      Bucket(from, to, nodes, cache, LocalDateTime.now(clock)).asRight
  }

  implicit val kbucketEq: Eq[KBucket] = Eq.fromUniversalEquals
}
