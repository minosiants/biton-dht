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

import scodec.bits.BitVector

import types.{ NodeId, Prefix }

trait PrefixSyntax {
  implicit def prefixSyntax(prefix: Prefix): PrefixOps = new PrefixOps(prefix)
}

final class PrefixOps(val prefix: Prefix) extends AnyVal {
  def toNodeId: NodeId       = NodeId(BitVector(prefix.value.toByteArray))
  def +(v: Prefix): Prefix   = Prefix(prefix.value + v.value)
  def +(v: Int): Prefix      = Prefix(prefix.value + v)
  def /(n: Int): Prefix      = Prefix(prefix.value / n)
  def <(v: Prefix): Boolean  = prefix.value < v.value
  def >(v: Prefix): Boolean  = prefix.value > v.value
  def >=(v: Prefix): Boolean = prefix.value >= v.value
  def <=(v: Prefix): Boolean = prefix.value <= v.value
}
