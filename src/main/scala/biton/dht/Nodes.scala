/*
 * Copyright 2020 Kaspar Minosiants
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
