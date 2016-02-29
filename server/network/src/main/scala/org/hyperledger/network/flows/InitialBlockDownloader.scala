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

import akka.actor._
import org.hyperledger.common.{BID, Block}
import org.hyperledger.network.server.InitialBlockDownloaderState._
import org.hyperledger.network.server.{BlockStoreWorker, InitialBlockDownloaderConfig, InitialBlockDownloaderState}

import scala.collection.immutable.Queue
import scalaz.Scalaz._
import scalaz._

object InitialBlockDownloader {

  sealed trait IBDMessage
  case class RegisterPeer(peerActor: ActorRef) extends IBDMessage
  case object ContinueAsNormal extends IBDMessage
  case object CheckMissingHeaders extends IBDMessage
  case class RequestBlockDownloadBatch(bids: List[BID]) extends IBDMessage
  case class CompleteBlockDownload(blocks: Block) extends IBDMessage

  def props(blockStore: ActorRef, config: InitialBlockDownloaderConfig) =
    Props(classOf[InitialBlockDownloader], blockStore, config)
}

import org.hyperledger.network.flows.InitialBlockDownloader._

/**
 * Actor for keeping and managing the state of the initial block download phase.
 */
class InitialBlockDownloader(blockStore: ActorRef, config: InitialBlockDownloaderConfig) extends Actor with ActorLogging {

  var state = InitialBlockDownloaderState.empty

  var peers = Set.empty[ActorRef]

  def receive = {
    case CheckMissingHeaders =>
      log.debug("Received CheckMissingHeaders")
      runBlockDownloadAction(fillPendingDownloads.map((_, Queue.empty)))

    case RegisterPeer(peer: ActorRef) =>
      log.debug(s"Registering peer $peer")
      context watch peer
      peers += peer
      runBlockDownloadAction(for {
        reassignedDLs <- connectionsChanged(peers)
        newDLs <- fillPendingDownloads
      } yield (reassignedDLs ++ newDLs, Queue.empty))

    case Terminated(peer) =>
      log.debug(s"peer terminated $peer")
      peers -= peer
      runBlockDownloadAction(for {
        reassignedDLs <- connectionsChanged(peers)
        newDLs <- fillPendingDownloads
      } yield (reassignedDLs ++ newDLs, Queue.empty))

    case CompleteBlockDownload(block) =>
      log.debug(s"Received CompleteBlockDownload(${block.getID})")
      runBlockDownloadAction(for {
        toDownload <- newBlock(block)
        toStore <- requestForStore
      } yield (toDownload, toStore))

    case BlockStoreWorker.BlockStoreRequest =>
      log.debug("Received BlockStoreWorker.BlockStoreRequest")
      runBlockDownloadAction(for {
        toDownload <- blocksStored
        toStore <- requestForStore
      } yield (toDownload, toStore))
  }

  def runBlockDownloadAction(action: BDLRWS[(List[PendingDownload], Queue[Block])]) = {
    val (logs, (toDownload, toStore), newDLState) = action.run(config, state)

    logs foreach log.debug
    if (toStore.nonEmpty) {
      blockStore ! BlockStoreWorker.StoreBlocks(toStore)
    }

    toDownload.foreach(dl => dl.connection ! RequestBlockDownloadBatch(dl.hashes))

    state = newDLState
    if (state.fullSize == 0)
      peers.foreach(_ ! ContinueAsNormal)
  }
}
