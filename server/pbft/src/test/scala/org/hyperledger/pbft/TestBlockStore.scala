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
package org.hyperledger.pbft

import akka.actor.ActorRef
import org.hyperledger.common._
import org.hyperledger.network.InventoryVector
import scodec.Attempt

import scala.concurrent.Future

class TestBlockStore extends PbftBlockstoreInterface {

  import TestBlockStore._

  val genesis: List[(Block, List[Commit])] = List((genesisBlock, List.empty))
  var blockStore: List[(Block, List[Commit])] = genesis

  override def store(block: Block, commits: List[Commit]): Future[Boolean] = {
    blockStore = blockStore :+ (block, commits)
    Future.successful(true)
  }

  override def validateBlock(block: Block): Future[Boolean] =
    Future.successful(block.getID != invalidBID)

  override def onBlockAdded(blockId: BID)(f: () => Unit): Unit = f()

  override def hasBlock(blockId: BID): Future[Boolean] =
    Future.successful(blockStore.exists { _._1.getID == blockId } )

  override def getCommits(blockId: BID): Attempt[List[Commit]] =
    Attempt.Successful(blockStore.filter { _._1.getID == blockId }.head._2)

  override def headerLocatorHashes: List[BID] =
    List.empty

  override def getHighestBlock: Block =
    blockStore.last._1

  override def recover(recoveryActor: ActorRef): Future[Unit] =
    Future.successful(())

  def recoverTo(blockId: BID, recoveryActor: ActorRef, onComplete: () => Unit): Future[String] = {
    onComplete()
    Future.successful("test")
  }

  def reset() =
    blockStore = genesis

  def fetchBlock(bid: BID): Future[Option[Block]] = Future.successful(None)
  def fetchTx(tid: TID): Future[Option[Transaction]] = Future.successful(None)

  def addTransactions(txs: List[Transaction]): Future[(List[TID], List[TID])] =
    Future.successful((List.empty, List.empty))
  override def filterUnkown(inventories: List[InventoryVector]): Future[List[InventoryVector]] = Future.successful(Nil)
  override def hasTransaction(txId: TID): Future[Boolean] = Future.successful(true)
}

object TestBlockStore {

  import DummyData._

  val invalidBID = BID.createFromSafeArray(dummyHash(99))
  val invalidBlockHeader = new BitcoinHeader(1, invalidBID, dummyMerkleRoot, 0, 1, 2)
  val invalidBlock = new Block(invalidBlockHeader, dummyTransactionList)

  val genesisBID = BID.createFromSafeArray(dummyHash(100))
  val genesisBlockHeader = new BitcoinHeader(1, genesisBID, dummyMerkleRoot, 0, 1, 2)
  val genesisBlock = new Block(genesisBlockHeader, dummyTransactionList)

}
