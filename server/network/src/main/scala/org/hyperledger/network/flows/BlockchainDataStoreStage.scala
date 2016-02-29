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

import org.hyperledger.common.{ Block, Transaction }
import org.hyperledger.network.InventoryVectorType.MSG_BLOCK
import org.hyperledger.network.Messages._
import org.hyperledger.network.server.TransactionDownloadState._
import org.hyperledger.network.server.{ BlockDownloadState, TransactionDownloadState }
import org.hyperledger.network.{ HyperLedger, InventoryVector }

import org.hyperledger.network.Implicits._

import scala.collection.JavaConverters._
import scala.concurrent.{ ExecutionContext, Future }
import scalaz.Scalaz._

object BlockchainDataStoreStage {
  case class BlockStoreState(blocks: BlockDownloadState = BlockDownloadState.empty,
    txs: TransactionDownloadState = TransactionDownloadState.empty)

  def apply(hyperLedger: HyperLedger, broadcast: BlockchainMessage => Unit)(implicit ec: ExecutionContext) =
    new BlockchainDataStoreStage(hyperLedger, broadcast)(ec)
}
import org.hyperledger.network.flows.BlockchainDataStoreStage._

class BlockchainDataStoreStage(hyperLedger: HyperLedger, broadcast: BlockchainMessage => Unit)(implicit ec: ExecutionContext)
  extends AsyncStatefulStage[BlockchainMessage, BlockStoreState](BlockStoreState()) {
  import AsyncStatefulStage._

  override val inputProcessor: DataProcessor[BlockchainMessage, BlockStoreState] = {
    case i: InvMessage   => receiveInvs(i.inventory)
    case t: TxMessage    => receiveTx(t.tx)
    case b: BlockMessage => receiveBlock(b.block)
  }

  private def receiveInvs(invs: List[InventoryVector])(state: BlockStoreState) = {
    hyperLedger.filterUnknown(invs).map { missingInvs =>
      missingInvs
        .partition(_.typ == MSG_BLOCK)
        .rightMap(_.filterNot(i => state.txs.isKnown(i.hash.toTID)))
        .fold(_ ++ _) match {
          case Nil => Nil
          case is  => List(GetDataMessage(is))
        }
    }.map((state, _))
  }

  private def receiveTx(tx: Transaction)(state: BlockStoreState) = {
    hyperLedger.filterUnknown(tx.sourceInventory).map(_.map(_.hash.toTID)) flatMap { missingIDs =>
      state.txs.transactionReceived(tx, missingIDs).rightMap {
        case Noop                      => Future.successful(Nil)
        case DoubleSpend(ids)          => Future.successful(Nil)
        case DownloadTransactions(txs) => Future.successful(List(GetDataMessage.txs(txs)))
        case StoreTransactions(txs) => hyperLedger.addTransactions(txs).map {
          case (_, Nil)          => Nil
          case (Nil, success)    => List(InvMessage.txs(success))
          case (failed, success) => List(InvMessage.txs(success))
          // TODO reject message for faulty txs?
        }.map(l => l.foreach(broadcast)).map(_ => Nil)
      }.fold((newState, f) => f.map((state.copy(txs = newState), _)))
    }
  }

  private def receiveBlock(block: Block)(state: BlockStoreState) =
    blockStatus(block).flatMap {
      case (known, connected) if known || connected =>
        val (newBlocksState, toStore) =
          if (known) state.blocks.knownBlockReceived(block)
          else state.blocks.connectedBlockReceived(block)

        hyperLedger.addBlocks(toStore).map {
          case (_, Nil) => Nil
          case (failed, success) =>
            broadcast(InvMessage.blocks(success.flatMap(_.getAddedToTrunk.asScala)))
            Nil
          // TODO handle block store failure with either RejectMessage or ctx.fail()
        }.map(state.copy(blocks = newBlocksState) -> _)

      case _ =>
        val (newBlocksState, downloadPrevious) = state.blocks.blockReceived(block)
        if (downloadPrevious) Future.successful((state.copy(blocks = newBlocksState), List(GetDataMessage.blocks(block.getPreviousID))))
        else Future.successful((state.copy(blocks = newBlocksState), Nil))
    }

  private def blockStatus(block: Block) = for {
    known <- hyperLedger.hasBlock(block.getID)
    connected <- if (known) Future.successful(true) else hyperLedger.hasBlock(block.getPreviousID)
  } yield (known, connected)

}
