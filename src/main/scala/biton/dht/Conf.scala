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

import java.nio.file.Path

import io.estatico.newtype.macros._

import scala.concurrent.duration.FiniteDuration
import Conf._
import biton.dht.types.NodeId
import com.comcast.ip4s.Port
import scala.concurrent.duration._

final case class Conf(
    /**
      * Id of this node
      */
    nodeId: NodeId,
    /**
      * Port of this node
      */
    port: Port,
    /**
      * Duration of when kbucket and node are good
      */
    goodDuration: GoodDuration,
    /**
      * Interval of refreshing outdated kbuckets
      */
    refreshTableDelay: RefreshTableDelay,
    /**
      * Interval of saving table to file
      */
    saveTableDelay: SaveTableDelay,
    /**
      * Destination where serialized table is stored
      */
    saveTableDir: Path,
    /**
      * Interval of NodeInfoCache expiration
      */
    cacheExpiration: CacheExpiration,
    /**
      * Duration when secret is valid
      */
    secretExpiration: SecretExpiration
) {
  def setNodeId(nodeId: NodeId): Conf = copy(nodeId = nodeId)
  def setPort(port: Port): Conf       = copy(port = port)
  def setGoodDuration(goodDuration: GoodDuration): Conf =
    copy(goodDuration = goodDuration)
  def setRefreshTableDelay(refreshTableDelay: RefreshTableDelay): Conf =
    copy(refreshTableDelay = refreshTableDelay)
  def setSaveTableDelay(saveTableDelay: SaveTableDelay): Conf =
    copy(saveTableDelay = saveTableDelay)
  def setSaveTableDir(path: Path): Conf = copy(saveTableDir = path)
  def setCacheExpiration(cacheExpiration: CacheExpiration): Conf =
    copy(cacheExpiration = cacheExpiration)
  def setSecretExpiration(secretExpiration: SecretExpiration): Conf =
    copy(secretExpiration = secretExpiration)
}

object Conf {

  @newtype final case class RefreshTableDelay(value: FiniteDuration)
  @newtype final case class SaveTableDelay(value: FiniteDuration)
  @newtype final case class CacheExpiration(value: FiniteDuration)
  @newtype final case class GoodDuration(value: FiniteDuration)
  @newtype final case class SecretExpiration(value: FiniteDuration)

  def default(): Conf = Conf(
    nodeId = NodeId.gen(),
    port = Port(6881).get,
    goodDuration = GoodDuration(15.minutes),
    refreshTableDelay = RefreshTableDelay(2.minutes),
    saveTableDelay = SaveTableDelay(1.minute),
    saveTableDir = Path.of(System.getProperty("user.home"), ".biton", "dht"),
    cacheExpiration = CacheExpiration(10.minutes),
    secretExpiration = SecretExpiration(5.minutes)
  )
}
