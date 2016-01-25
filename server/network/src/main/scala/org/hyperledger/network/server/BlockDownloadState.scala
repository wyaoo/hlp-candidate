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

import org.hyperledger.common.{BID, Block}

import scala.annotation.tailrec

object BlockDownloadState {
  def empty = BlockDownloadState()
}

case class BlockDownloadState(dependencies: Map[BID, List[Block]] = Map.empty.withDefault(_ => List.empty[Block]),
                               pendingBlocks: Set[BID] = Set.empty) {
  def isPending: BID => Boolean = pendingBlocks

  /**
   * Block received which nor itself neither its ancestor is known
   */
  def blockReceived(block: Block): (BlockDownloadState, Boolean) = {
    val prev = block.getPreviousID
    val newDeps = dependencies.updated(prev, dependencies.getOrElse(prev, Nil) :+ block)
    (copy(dependencies = newDeps, pendingBlocks = pendingBlocks + block.getID),
      !dependencies.contains(block.getPreviousID))
  }

  def connectedBlockReceived(block: Block): (BlockDownloadState, List[Block]) = {
    @tailrec
    def collectDeps(blocks: List[BID], acc: List[Block]): List[Block] = {
      blocks.foldLeft(List.empty[Block]) { (acc, bid) =>
        acc ++ dependencies.getOrElse(bid, Nil)
      } match {
        case Nil       => acc
        case newBlocks => collectDeps(newBlocks.map(_.getID), acc ++ newBlocks)
      }
    }

    val toRemove = block +: collectDeps(List(block.getID), Nil)
    (copy(dependencies = dependencies -- toRemove.map(_.getID), pendingBlocks -- toRemove.map(_.getID)),
      toRemove)
  }

  def knownBlockReceived(block: Block) = connectedBlockReceived(block)
}
