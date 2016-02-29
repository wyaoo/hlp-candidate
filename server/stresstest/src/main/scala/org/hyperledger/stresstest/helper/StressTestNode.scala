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

package org.hyperledger.stresstest.helper

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{BidiFlow, Flow, Keep, Tcp}
import akka.stream.testkit.scaladsl._
import org.hyperledger.network.Messages._
import org.hyperledger.network.flows.{HandshakeStage, ScodecStage}
import org.hyperledger.network.server.PeerInterface
import org.hyperledger.network.server.PeerInterface.Misbehavior
import org.hyperledger.network.{Messages, NetworkAddress, Rejection, Version}

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.Random
import scalaz.\/

object StressTestNode {

  val nonceCounter = new AtomicLong(0)

  class StressTestPeer extends PeerInterface[BlockchainMessage] {
    lazy val version = Version.forNow(
      60002L,
      Version.SIMPLE_NODE,
      NetworkAddress.forVersion(Version.SIMPLE_NODE, new InetSocketAddress("0.0.0.0", 0)),
      NetworkAddress.forVersion(Version.SIMPLE_NODE, new InetSocketAddress("0.0.0.0", 0)),
      nonceCounter.incrementAndGet(),
      "/HyperLedgerStressTest:2.0/",
      0,
      relay = true)

    override def inbound: Boolean = false
    override def ourVersion: BlockchainMessage = VersionMessage(version)
    override def misbehavior(m: Misbehavior): Unit = println(s"Misbehaviour: $m")
    override def latencyReport(latestLatency: FiniteDuration): Unit = ()
    override def versionReceived(version: Version) = Future.successful(\/.right(List(VerackMessage())))
    override def broadcast(m: BlockchainMessage): Unit = ()
  }

  import HandshakeStage._

  val handshakeHandler: HandshakeHandler[BlockchainMessage] = {
    case VersionMessage(v) => VersionReceived(v)
    case VerackMessage() => VersionAcked
    case RejectMessage(r@Rejection("version", code, reason, data)) => VersionRejected(r)
    case m => NonHandshakeMessage(m)
  }


  def connectToPeer[M](address: InetSocketAddress, flow: Flow[BlockchainMessage, BlockchainMessage, M])
    (implicit sys: ActorSystem) = {
    implicit val am = ActorMaterializer()
    import sys.dispatcher

    val handshake = new HandshakeStage(new StressTestPeer, handshakeHandler)
    val baseFlow = ScodecStage.bidi(Messages.messageCodec(ServerProperties.regtestMagicNumber))
        .atop(BidiFlow.fromGraph(handshake))
        .atop(BidiFlow.fromGraph(new PassivePingPongStage[BlockchainMessage]({ case PingMessage(p) => PongMessage(p) })))

    val f = baseFlow.joinMat(flow)(Keep.right)
    Tcp(sys).outgoingConnection(address).joinMat(f)(Keep.both).run
  }
}
