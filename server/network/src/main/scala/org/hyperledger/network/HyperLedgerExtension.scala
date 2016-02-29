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
import akka.pattern.ask
import akka.util.Timeout
import org.hyperledger.common.{BID, Header}
import org.hyperledger.network.Messages._
import org.hyperledger.network.flows.InitialBlockDownloader
import org.hyperledger.network.flows.InitialBlockDownloader.RegisterPeer
import org.hyperledger.network.server.ServerActor.GetDownloader
import org.hyperledger.network.server.{BlockStoreWorker, InitialBlockDownloaderConfig, ServerActor}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Random

object HyperLedgerExtension extends ExtensionId[HyperLedgerExtension] with ExtensionIdProvider {
  override def createExtension(system: ExtendedActorSystem) = new HyperLedgerExtension(system)

  override def lookup() = HyperLedgerExtension

  override def get(system: ActorSystem) = apply(system)

  val UA = "/HyperLedger:2.0.0-SNAPSHOT/"
  val PROTOCOL_VERSION = 70002
  val INITIAL_BLOCK_DOWNLOAD_THRESHOLD = 144
}

trait IBDInterface {
  def addHeaders(headers: List[Header]): Future[Unit]
  def hasBlock(id: BID): Future[Boolean]
  def createGetHeadersRequest(): GetHeadersMessage
  def registerForInitialBlockDownload(stageActor: ActorRef): ActorRef
}

class HyperLedgerExtension(system: ExtendedActorSystem) extends Extension {
  import HyperLedgerExtension._

  val NONCE = Random.nextLong()

  val hyperLedgerCore = HyperLedgerCoreExtension(system)
  val settings = HyperLedgerExtensionSettings(system.settings.config)

  val api = hyperLedgerCore.api
  val coreAssembly = hyperLedgerCore.coreAssembly

  private val serverActor = system.actorOf(ServerActor.props, "HyperLedgerServer")
  lazy val blockDownloader = {
    implicit val timeout = Timeout(10 seconds)
    Await.result((serverActor ? GetDownloader).mapTo[ActorRef], 20 seconds)
  }

  // IBDInterface
  val ibd = new IBDInterface {
    override def createGetHeadersRequest() = GetHeadersMessage(BlockDataRequest(PROTOCOL_VERSION, api.headerLocatorHashes(), BID.INVALID))
    override def addHeaders(headers: List[Header]): Future[Unit] = api.addHeaders(headers)
    override def hasBlock(id: BID): Future[Boolean] = api.hasBlock(id)
    override def registerForInitialBlockDownload(stageActor: ActorRef): ActorRef = {
      blockDownloader ! RegisterPeer(stageActor)
      blockDownloader
    }
  }

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

  system.log.info(s"HyperLedger extension initialized. NONCE=${NONCE.toHexString}")
}

