/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hyperledger.network.server

import akka.actor._
import org.hyperledger.common.Block
import org.hyperledger.core.BlockStore

import scala.collection.immutable.Queue


object BlockStoreWorker {
  case object BlockStoreRequest
  case class StoreBlocks(blocks: Queue[Block])

  def props(blockStore: BlockStore) = Props(classOf[BlockStoreWorker], blockStore)
}

class BlockStoreWorker(blockStore: BlockStore) extends Actor with ActorLogging {
  import BlockStoreWorker._

  def receive = {
    case StoreBlocks(blocks) =>
      log.debug(s"Storing batch ${blocks.size} blocks")
      for (block <- blocks) {
        try {
          log.debug(s"Storing block ${block.getID}")
          blockStore.addBlock(block)
        } catch {
          case e: Exception => log.error(e, s"Error while storing block $block")
        }
      }
      sender() ! BlockStoreRequest
  }
}
