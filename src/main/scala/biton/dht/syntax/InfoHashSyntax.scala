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
