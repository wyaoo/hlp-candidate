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
import org.hyperledger.core.CoreOutbox.{BlockAdded, P2PEvent, ServerOutboxListener, TransactionAdded}
import org.hyperledger.network.Messages._
import org.hyperledger.network.flows._
import org.hyperledger.network.{HyperLedgerExtension, Version}

import scala.collection.JavaConverters._


object ServerActor {
  case class ServerBroadcast(message: BlockchainMessage, peerId: Option[Long])
  case object GetDownloader

  def props = Props(classOf[ServerActor])
}

class ServerActor extends Actor with ActorLogging {
  import ServerActor._
  val hyperLedger = HyperLedgerExtension(context.system)

  val blockStore = context.actorOf(Props(classOf[BlockStoreWorker], hyperLedger.api.blockStore).withDispatcher(hyperLedger.settings.dispatcherName))
  val blockDownloader = context.actorOf(
    InitialBlockDownloader.props(blockStore, InitialBlockDownloaderConfig(hyperLedger.api.missingBlocks)),
    "InitialBlockDownloader"
  )

  val solicitedConnections = context.actorOf(ConnectionManager.props)

  override def preStart(): Unit = {
    hyperLedger.coreAssembly.getCoreOutbox.addListener(new ServerOutboxListener {
      override def onP2PEvent(e: P2PEvent[_]): Unit = context.self ! e.getContent
    })
  }

  def receive = {
    case GetDownloader =>
      sender() ! blockDownloader
    case event: TransactionAdded =>
      solicitedConnections ! ServerBroadcast(TxMessage(event.getContent), None)

    case event: BlockAdded =>
      solicitedConnections ! ServerBroadcast(InvMessage.blocks(event.getContent.getAddedToTrunk.asScala), None)
  }
}
