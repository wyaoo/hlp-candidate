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

import org.hyperledger.common.{ BID, Block }

import akka.actor.ActorRef
import scala.collection.immutable.Queue
import scala.language.reflectiveCalls
import scalaz.Scalaz._
import scalaz._

case class InitialBlockDownloaderConfig(missingBlocks: Int => List[BID],
  downloadWindow: Int = 128,
  blockStoreQueueSize: Int = 16,
  batchSize: Int = 16)

/**
  * The block downloader maintain a state, which consists:
  * - connections: The active connections, can be used to send download requests to
  * - pendingDownloads: A list of PendingDownload, which contains the connection the
  *                     download request sent to, the requested block hashes, and the
  *                     already downloaded blocks
  * - blockStoreQueue: This queue holds the downloaded blocks until they got stored
  * - storePending: Blocks, which sent to the block persister for storing
  *
  * The block downloader responds to various events, all of which modifies this state:
  * - connectionsChanged: a new set of connections available, if a connection to a new peer
  *                       established or an existing connection is closed.
  *                       If a new connection is available, new PendingDownloads will be created.
  *                       If a connection is closed, the PendingDownloads for these connections
  *                       are reassigned to available peers.
  * - newBlock          : A block is downloaded by one of the connections.
  * - requestForStore   : The block store is idle, new blocks can be sent for storage if
  *                       available.
  */
object InitialBlockDownloaderState {
  object PendingDownload {
    def create(hashes: List[BID], connection: ActorRef) = new PendingDownload(hashes, connection, Nil)
  }
  case class PendingDownload(hashes: List[BID], connection: ActorRef, blocks: List[Block] = Nil) {
    def addBlock(block: Block) = copy(blocks = blocks :+ block)
  }

  object ConnectionChange {
    def apply: Set[ActorRef] => ConnectionChange = {
      case s if s.isEmpty => ConnectionChange(Stream.empty[ActorRef], Nil, Nil)
      case s              => ConnectionChange(Stream.continually(s).flatten, Nil, Nil)
    }
  }
  case class ConnectionChange(
    peers: Stream[ActorRef],
    allDownloads: List[PendingDownload],
    reassignedDownload: List[PendingDownload])

  type BDLRWS[A] = ReaderWriterState[InitialBlockDownloaderConfig, List[String], InitialBlockDownloaderState, A]
  val rws = ReaderWriterStateT.rwstMonad[Id, InitialBlockDownloaderConfig, List[String], InitialBlockDownloaderState]
  import rws._

  /**
    * When querying the missing blocks, we need to take into account the downloaded, but not yet stored blocks.
    * This function returns the number of hashes we need to request for the missing blocks call.
    */
  val missingBlocksNeeded: BDLRWS[Int] = asks(_.batchSize) flatMap { size =>
    gets(s => s.fullSize + (s.availablePeers.size * size))
  }

  // format: OFF
  private val pendingDownloadsUpdate: BDLRWS[List[PendingDownload]] = for {
    conf          <- ask
    missingHashes <- missingBlocksNeeded map conf.missingBlocks
    newDownloads  <- gets(_.createPendingDownloads(missingHashes, conf.batchSize))
    _             <- condLog(newDownloads.nonEmpty, s"Missing blocks needed to download ${newDownloads.flatMap(_.hashes)}")
    _             <- modify(_.addPendingDownloads(newDownloads))
  } yield newDownloads
  // format: ON

  val hasRoom: BDLRWS[Boolean] = apply2(asks(_.downloadWindow), gets(_.fullSize))(_ >= _)
  val fillPendingDownloads: BDLRWS[List[PendingDownload]] = ifM(hasRoom, pendingDownloadsUpdate, pure(List.empty))

  def orderBlocks(l: List[PendingDownload]) = l.flatMap { tdl =>
    tdl.hashes.map(h => tdl.blocks.find(_.getID == h).get)
  }

  /**
    * If a new block is downloaded, the block is added to the PendingDownload which is waiting for this block.
    * After updating the PendingDownloads list, it moves all ready PendingDownloads from the head of the list
    * to the `blockStoreQueue`, maintaining the order as the blocks requested.
    */
  // format: OFF
  def newBlock(block: Block): BDLRWS[List[PendingDownload]] = for {
    newPending <- gets(_.pendingDownloads.collect {
      case pdl if pdl.hashes.contains(block.getID) => pdl.addBlock(block)
      case pdl                                     => pdl
    })
    (toStore, stillPending) = newPending.partition(pdl => pdl.hashes.size == pdl.blocks.size).leftMap(orderBlocks)
    _                      <- modify(s => s.copy(pendingDownloads = stillPending, blockStoreQueue = s.blockStoreQueue ++ toStore))
    newDLs                 <- fillPendingDownloads
  } yield newDLs
  // format: ON

  /**
    * If the set of connections changes, we need to reassign pending downloads from the disappearing connections
    * to the active ones. The returned list of PendingDownloads are these reassigned tasks.
    */
  def connectionsChanged(newConnections: Set[ActorRef]): BDLRWS[List[PendingDownload]] = ReaderWriterState {
    case (_, state) =>
      val change = state.pendingDownloads.foldLeft(ConnectionChange(newConnections)) {
        case (c, pdl) if newConnections(pdl.connection) => c.copy(allDownloads = c.allDownloads :+ pdl)
        case (c, pdl) =>
          val newPdl = pdl.copy(connection = c.peers.head)
          ConnectionChange(c.peers.tail, c.allDownloads :+ newPdl, c.reassignedDownload :+ newPdl)
      }

      (change.reassignedDownload.map(pdl => s"Reassigning block download task to peer ${pdl.connection}, blocks: ${pdl.hashes}"),
        change.reassignedDownload,
        state.copy(connections = newConnections, pendingDownloads = change.allDownloads))
  }

  val hasNoStorePending = gets(_.storePending.isEmpty)

  /**
    * Moves the first `n` element from the blockStoreQueue to the storePending queue.
    */
  // format: OFF
  val moveToStorage: BDLRWS[Queue[Block]] = for {
    storeQueueSize         <- asks(_.blockStoreQueueSize)
    toStoreAndStillPending <- gets(_.blockStoreQueue.splitAt(storeQueueSize))
    (toStore, stillPending) = toStoreAndStillPending
    _                      <- condLog(toStore.nonEmpty, s"Sending ${toStore.size} to store")
    _                      <- modify(_.copy(storePending = toStore, blockStoreQueue = stillPending))
  } yield toStore
  // format: ON

  val requestForStore: BDLRWS[Queue[Block]] = ifM(hasNoStorePending, moveToStorage, pure(Queue.empty))

  val blocksStored: BDLRWS[List[PendingDownload]] = modify(_.copy(storePending = Queue.empty)) flatMap (_ => fillPendingDownloads)

  def empty = InitialBlockDownloaderState(connections = Set.empty)

  def condLog(predicate: => Boolean, msg: => String) = if (predicate) tell(List(msg)) else tell(Nil)

}

case class InitialBlockDownloaderState(connections: Set[ActorRef],
  pendingDownloads: List[InitialBlockDownloaderState.PendingDownload] = Nil,
  blockStoreQueue: Queue[Block] = Queue.empty,
  storePending: Queue[Block] = Queue.empty) {
  import InitialBlockDownloaderState.PendingDownload

  def busyPeers: Set[ActorRef] = pendingDownloads.map(_.connection).toSet

  /**
    * Peers available for download, that is peers which doesn't have pending downloads.
    */
  def availablePeers: Set[ActorRef] = connections diff busyPeers

  def fullSize: Int = pendingDownloads.map(_.hashes.size).sum + blockStoreQueue.size + storePending.size

  /**
    * Block hashes which are either already downloaded or download is pending
    */
  def downloadedHashes: Set[BID] = (blockStoreQueue.map(_.getID) ++
    pendingDownloads.flatMap(_.blocks.map(_.getID)) ++
    storePending.map(_.getID)).toSet

  def createPendingDownloads(missingHashes: List[BID], batchSize: Int) = missingHashes.filterNot(downloadedHashes)
    .grouped(batchSize)
    .zip(availablePeers.iterator)
    .map((PendingDownload.create _).tupled)
    .toList

  def addPendingDownloads(dls: List[PendingDownload]) = copy(pendingDownloads = pendingDownloads ++ dls)
}

