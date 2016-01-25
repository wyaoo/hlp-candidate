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
package org.hyperledger.network

import java.net.InetSocketAddress

import akka.actor._
import org.hyperledger.common.BID
import org.hyperledger.core.CoreOutbox.{P2PEvent, ServerOutboxListener}
import Messages._
import org.hyperledger.network.server.ServerActor
import org.hyperledger.network.server.ServerActor.Start

import scala.util.Random


object HyperLedgerExtension extends ExtensionId[HyperLedgerExtension] with ExtensionIdProvider {
  override def createExtension(system: ExtendedActorSystem) = new HyperLedgerExtension(system)

  override def lookup() = HyperLedgerExtension

  override def get(system: ActorSystem) = apply(system)
}

class HyperLedgerExtension(system: ExtendedActorSystem) extends Extension {

  val UA = "/HyperLedger:2.0.0-SNAPSHOT/"
  val PROTOCOL_VERSION = 70002
  val NONCE = Random.nextLong()

  val hyperLedgerCore = HyperLedgerCoreExtension(system)

  val settings = HyperLedgerExtensionSettings(system.settings.config)

  private[HyperLedgerExtension] var serverActor: ActorRef = _

  def api = hyperLedgerCore.api

  def coreAssembly = hyperLedgerCore.coreAssembly

  /**
   * This method initializes the extension by also initializing the core services by loading the configuration,
   * and starting every core service. If you want to create your own CoreAssembly and want to initialize it yourself,
   * the call the other initialize method.
   */
  def initialize(): Unit = {
    hyperLedgerCore.initialize()

    serverActor = system.actorOf(ServerActor.props)

    hyperLedgerCore.coreAssembly.getCoreOutbox.addListener(new ServerOutboxListener {
      override def onP2PEvent(e: P2PEvent[_]): Unit = serverActor ! e
    })

    serverActor ! Start

  }

  def createGetHeadersRequest = GetHeadersMessage(BlockDataRequest(PROTOCOL_VERSION, api.headerLocatorHashes(), BID.INVALID))

  def ourVersion(peerAddress: InetSocketAddress) = Version.forNow(
    PROTOCOL_VERSION,
    services = Version.SIMPLE_NODE,
    addrRecv = NetworkAddress.forVersion(Version.SIMPLE_NODE, peerAddress),
    addrFrom = NetworkAddress.forVersion(Version.SIMPLE_NODE, settings.localAddress),
    nonce = NONCE,
    userAgent = UA,
    startHeight = api.blockStore.getFullHeight,
    relay = true)

  def versionPredicate(v: Version): Boolean = true
}

