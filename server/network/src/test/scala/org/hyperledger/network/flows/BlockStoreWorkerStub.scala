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

package org.hyperledger.network.flows

import akka.actor.Actor
import org.hyperledger.common.{BID, Block}
import org.hyperledger.network.server.BlockStoreWorker.{StoreBlocks, BlockStoreRequest}

class BlockStoreWorkerStub extends Actor {
  var missingBlocks = List.empty[BID]
  var storedBlocks = List.empty[Block]

  def receive = {
    case StoreBlocks(blocks) =>
      val storeIDs = blocks.map(_.getID).toSet
      missingBlocks = missingBlocks.filterNot(storeIDs)
      storedBlocks ++= blocks
      sender() ! BlockStoreRequest
  }
}

