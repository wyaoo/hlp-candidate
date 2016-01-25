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

import java.net.InetSocketAddress

import _root_.akka.actor._
import org.hyperledger.common._
import org.hyperledger.core.BlockStore.BlockListener
import org.hyperledger.core.BlockStoredInfo
import org.hyperledger.network._
import scodec.{Err, Attempt}
import scodec.bits.BitVector

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random
import scalaz.\/

object PbftExtension extends ExtensionId[PbftExtension] with ExtensionIdProvider {
  override def createExtension(system: ExtendedActorSystem) = new PbftExtension(system)
  override def get(system: ActorSystem) = apply(system)
  override def lookup(): ExtensionId[_ <: Extension] = PbftExtension
}

class PbftExtension(system: ExtendedActorSystem) extends Extension {
  val settings = PbftSettings.fromConfig(system.settings.config)

  // TODO use the maven version in the wire format version message
  val UA = "/HyperLedger:2.0.0-SNAPSHOT(PBFT)/"
  val PROTOCOL_VERSION = 70002
  val NONCE = Random.nextLong()

  val versionPredicate: Messages.VersionMessage => Boolean = _ => true
  val versionP: Version => String \/ Unit = _ => \/.right(())
  val hyperLedgerCore = HyperLedgerCoreExtension(system)
  var blockStoreConn: PbftBlockstoreInterface = _

  def initialize(): Unit = {
    hyperLedgerCore.initialize()
    blockStoreConn = new PbftBlockstoreConnection(hyperLedgerCore.hyperLedger, settings, PROTOCOL_VERSION, hyperLedgerCore.hyperLedgerExecutionContext)
  }

  def ourVersion(peerAddress: InetSocketAddress) = Version.forNow(
    PROTOCOL_VERSION,
    services = Version.SIMPLE_NODE,
    addrRecv = NetworkAddress.forVersion(Version.SIMPLE_NODE, peerAddress),
    addrFrom = NetworkAddress.forVersion(Version.SIMPLE_NODE, settings.bindAddress),
    nonce = NONCE,
    userAgent = UA,
    startHeight = hyperLedgerCore.api.blockStore.getFullHeight,
    relay = true)
}

trait PbftBlockstoreInterface {
  def fetchTx(tid: TID): Future[Option[Transaction]]
  def filterUnkown(inventories: List[InventoryVector]): Future[List[InventoryVector]]
  def hasTransaction(txId: TID): Future[Boolean]
  def hasBlock(blockId: BID): Future[Boolean]
  def headerLocatorHashes: List[BID]
  def getHighestBlock: Block
  def addTransactions(txs: List[Transaction]): Future[(List[TID], List[TID])]

  def store(block: Block, commits: List[Commit]): Future[Boolean]
  def getCommits(blockId: BID): Attempt[List[Commit]]
  def validateBlock(block: Block): Future[Boolean]
  def onBlockAdded(blockId: BID)(f: () => Unit)
  def recover(recoveryActor: ActorRef): Future[Unit]
  def recoverTo(blockId: BID, recoveryActor: ActorRef, onComplete: () => Unit): Future[String]
}

class PbftBlockstoreConnection(hyperLedger: HyperLedger,
  settings: PbftSettings,
  protocolVersion: Int,
  implicit val executionContext: ExecutionContext)
  extends PbftBlockstoreInterface {

  def store(block: Block, commits: List[Commit]): Future[Boolean] = {
    hyperLedger.addBlocks(List(block))
      .flatMap { x =>
        codecs.varIntSizeSeq(Commit.codec).encode(commits).fold(
          err => Future.failed(new HyperLedgerException(err.message)),
          encoded => Future.successful(encoded))
      }.map { encoded => hyperLedger.blockStore.addMiscData(block.getID, encoded.toByteArray) }
  }

  def fetchTx(tid: TID): Future[Option[Transaction]] = hyperLedger.fetchTx(tid)
  def filterUnkown(inventories: List[InventoryVector]): Future[List[InventoryVector]] = hyperLedger.filterUnknown(inventories)
  def hasTransaction(txId: TID): Future[Boolean] = hyperLedger.hasTransaction(txId)
  def hasBlock(blockId: BID): Future[Boolean] = hyperLedger.hasBlock(blockId)
  def headerLocatorHashes: List[BID] = hyperLedger.headerLocatorHashes()
  def getHighestBlock: Block = hyperLedger.blockStore.getHighestBlock
  def addTransactions(txs: List[Transaction]) = hyperLedger.addTransactions(txs)

  def getCommits(blockId: BID): Attempt[List[Commit]] = {
    val encoded = Option(hyperLedger.blockStore.getMiscData(blockId))
    encoded match {
      case Some(data) => codecs.varIntSizeSeq(Commit.codec).decode(BitVector(data)).map { _.value }
      case None => Attempt.failure(Err(s"Commits not stored for $blockId"))
    }
  }

  def validateBlock(block: Block): Future[Boolean] = Future(hyperLedger.blockStore.validateBlock(block))



  def onBlockAdded(blockId: BID)(f: () => Unit) = {

    val listener = new BlockListener {
      override def blockStored(content: BlockStoredInfo): Unit = {
        if (content.getAddedToTrunk.contains(blockId)) {
          hyperLedger.blockStore.removeBlockListener(this)
          f()
        }
      }
    }

    hyperLedger.blockStore.addBlockListener(listener)
  }

  def recover(recoveryActor: ActorRef): Future[Unit] = {
    recoveryActor ! GetBlocksMessage(BlockDataRequest(protocolVersion, headerLocatorHashes, getHighestBlock.getID))
    // TODO when is it ready?
    Future.successful(())
  }

  def recoverTo(blockId: BID, recoveryActor: ActorRef, onComplete: () => Unit): Future[String] = {
    hasBlock(blockId).flatMap {
      case true if blockId == getHighestBlock.getID =>
        onComplete()
        Future.successful("already at newest version")
      case true =>
        Future.failed(new HyperLedgerException("Recover to old version"))
      case false =>
        onBlockAdded(blockId)(onComplete)
        recoveryActor ! GetBlocksMessage(BlockDataRequest(protocolVersion, headerLocatorHashes, getHighestBlock.getID))
        Future.successful("successful")
    }
  }

}
