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

import scodec.bits.BitVector

trait TableFunctions {
  def formatBucket(kb: KBucket): String = {
    val from    = BitVector(kb.from.value.toByteArray).toBin
    val to      = BitVector(kb.to.value.toByteArray).toBin
    val nodeIds = kb.nodes.value.map(_.node.nodeId.value.toBin).mkString("\n")

    s"""
       |===========================================
       |bucket
       |$from = $to
       |${kb.from} = ${kb.to}
       |------------------------------------------
       |nodeId
       |$nodeIds
       |===========================================
       |""".stripMargin
  }

  def formatPrfexes(t: Table): String = {
    t.kbuckets.map(_.from.value).toVector.mkString("\n")
  }
}
