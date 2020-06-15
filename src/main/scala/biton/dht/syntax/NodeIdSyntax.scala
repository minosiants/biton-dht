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
package syntax

import biton.dht.types.{ Distance, NodeId, Prefix }

trait NodeIdSyntax {
  implicit def nodeIdSyntax(nodeId: NodeId): NodeIdOps = new NodeIdOps(nodeId)
}

final class NodeIdOps(val nodeId: NodeId) extends AnyVal {
  def toBigInt: BigInt         = BigInt(1, nodeId.value.toByteArray)
  def toPrefix: Prefix         = Prefix(toBigInt)
  def ^(other: NodeId): NodeId = NodeId(nodeId.value ^ other.value)
  def distance(other: NodeId): Distance =
    Distance(
      BigInt(1, nodeId.value.toByteArray) ^ BigInt(1, other.value.toByteArray)
    )

}
