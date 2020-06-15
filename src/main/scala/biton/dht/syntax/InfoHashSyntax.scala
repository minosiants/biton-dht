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

import biton.dht.protocol.InfoHash
import biton.dht.types.NodeId

trait InfoHashSyntax {
  implicit def infoHashSyntax(infoHash: InfoHash): InfoHashOps =
    new InfoHashOps(infoHash)

}

final class InfoHashOps(val infoHash: InfoHash) extends AnyVal {
  def toNodeId: NodeId = NodeId(infoHash.value)
}
