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
import akka.stream.scaladsl.Tcp
import org.hyperledger.common.Block
import org.hyperledger.core.CoreOutbox.{BlockAdded, TransactionAdded}
import org.hyperledger.network.{Version, Messages, HyperLedgerExtension}
import Messages._
import org.hyperledger.network.flows._
import org.hyperledger.network.server.BlockStoreWorker.BlockStoreRequest
import org.hyperledger.network.server.InitialBlockDownloaderState.{BDLRWS, PendingDownload, _}

import scala.collection.JavaConverters._
import scala.collection.immutable.Queue
import scalaz.Scalaz._
import scalaz._

sealed trait ServerState
case object ServerStarting extends ServerState
case object InitialBlockDownload extends ServerState
case object ServerRunning extends ServerState

sealed trait ServerData {
  def connections: Set[Long]
  def withConnection(c: Set[Long]): ServerData
}
case class SimpleServerData(connections: Set[Long] = Set.empty) extends ServerData {
  def withConnection(c: Set[Long]) = copy(connections = c)
}
case class IBDServerData(connections: Set[Long],
                         blockDownloaderState: InitialBlockDownloaderState = InitialBlockDownloaderState.empty)
  extends ServerData {
  def withConnection(c: Set[Long]) = copy(connections = c)
  def withDownloadState(bdl: InitialBlockDownloaderState) = copy(blockDownloaderState = bdl)
}

object ServerActor {
  case object Start
  case class PeerControlMessage(message: ControlMessage, peer: Version, peerActor: ActorRef)
  case class ServerControlMessage(message: ControlMessage, peerId: Option[Long])

  def props = Props(classOf[ServerActor])
}

class ServerActor extends LoggingFSM[ServerState, ServerData] {
  import ServerActor._

  val hyperLedger = HyperLedgerExtension(context.system)

  val DOWNLOAD_WINDOW = 128
  val BATCH_SIZE = 16
  val blockDownloadConfig = InitialBlockDownloaderConfig(hyperLedger.api.missingBlocks, DOWNLOAD_WINDOW, BATCH_SIZE)

  val blockStore = context.actorOf(Props[BlockStoreWorker].withDispatcher(hyperLedger.settings.dispatcherName))
  var solicitedConnections: ActorRef = _ //context.actorOf(SolicitedConnectionManager.props(context.self))

  startWith(ServerStarting, SimpleServerData())

  when(ServerStarting) {
    //case Event(Start, d)                    => goto(InitialBlockDownload) using IBDServerData(d.connections)
    case Event(Start, d)                    => goto(InitialBlockDownload) using IBDServerData(d.connections)
  }

  when(InitialBlockDownload) {
    case Event("startibd", d: IBDServerData) =>
      runBlockDownloadAction(d, for {
        reassignedDLs <- connectionsChanged(d.connections)
        newDLs <- fillPendingDownloads
      } yield (reassignedDLs ++ newDLs, Queue.empty))

    case Event(PeerControlMessage(HeadersDownloadComplete, version, actor), d: IBDServerData) =>

      log.info(s"Headers downloaded from peer ${version.nonce.toHexString}")
      runBlockDownloadAction(d, for {
        reassignedDLs <- connectionsChanged(d.connections)
        newDLs <- fillPendingDownloads
      } yield (reassignedDLs ++ newDLs, Queue.empty))

    case Event(PeerControlMessage(BlockReceived(block), version, actor), d: IBDServerData) =>
      runBlockDownloadAction(d, for {
        toDownload <- newBlock(block)
        toStore <- requestForStore
      } yield (toDownload, toStore))

    case Event(BlockStoreRequest, d: IBDServerData) => runBlockDownloadAction(d, for {
      toDownload <- blocksStored
      toStore <- requestForStore
    } yield (toDownload, toStore))
  }

  val IBD_THRESHOLD = 100

  when(ServerRunning) {
    case Event(PeerControlMessage(HeadersDownloadComplete, version, actor), d: SimpleServerData) =>
      if (hyperLedger.api.blockStore.getFullHeight + IBD_THRESHOLD < version.startHeight) {
        goto(InitialBlockDownload) using IBDServerData(d.connections)
      } else
        stay()

    case Event(event: TransactionAdded, _) =>
      solicitedConnections ! ServerControlMessage(SendBroadcast(TxMessage(event.getContent)), None)
      stay()

    case Event(event: BlockAdded, _) =>
      solicitedConnections ! ServerControlMessage(SendBroadcast(InvMessage.blocks(event.getContent.getAddedToTrunk.asScala)), None)
      stay()
  }

  whenUnhandled {
    case Event(c: ConnectionMaintenance, d) =>
      // TODO implement change in connection during IBD
      stay using d.withConnection(c.connectionIDs)
  }

  onTransition {
    case ServerStarting -> _ =>
      solicitedConnections = context.actorOf(SolicitedConnectionManager.props(context.self))
    case _ -> InitialBlockDownload => context.self ! "startibd"
  }

  def runBlockDownloadAction(oldState: IBDServerData, action: BDLRWS[(List[PendingDownload], Queue[Block])]) = {
    val (logs, (toDownload, toStore), newDLState) = action.run(blockDownloadConfig, oldState.blockDownloaderState)

    logs foreach log.debug

    // storing downloaded stuff
    if (toStore.nonEmpty) {
      log.debug(s"Sending blocks to blockstore: ${toStore.map(_.getID)}")
      blockStore ! BlockStoreWorker.StoreBlocks(toStore)
    }
    // sending download requests
    for (dl <- toDownload) {
      solicitedConnections ! ServerControlMessage(DownloadBlocks(dl.hashes), Some(dl.connection))
    }

    log.debug(s"[blockStoreQueue=${newDLState.blockStoreQueue.size}]")

    newDLState match {
      case dls if dls.fullSize == 0 => goto(ServerRunning) using SimpleServerData(oldState.connections)
      case dls                      => stay() using oldState.withDownloadState(newDLState)
    }
  }

  initialize()
}
