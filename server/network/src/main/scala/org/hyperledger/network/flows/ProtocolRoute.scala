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

import akka.stream.FanOutShape.Init
import akka.stream._
import akka.stream.stage.{OutHandler, InHandler, GraphStageLogic, GraphStage}
import org.hyperledger.network.Messages
import Messages._
import org.hyperledger.network.Messages

import scala.annotation.unchecked.uncheckedVariance

class ProtocolFanOutShape(_init: FanOutShape.Init[BlockchainMessage] = FanOutShape.Name("ProtocolFanOut")) extends FanOutShape[BlockchainMessage](_init) {

  override protected def construct(init: Init[BlockchainMessage]): ProtocolFanOutShape = new ProtocolFanOutShape(init)

  val dataOut = newOutlet[DataMessage @uncheckedVariance]("data.out")
  val addrOut = newOutlet[AddrMessage @uncheckedVariance]("addr.out")
  val alertOut = newOutlet[AlertMessage @uncheckedVariance]("alert.out")
  val getAddrOut = newOutlet[GetAddrMessage @uncheckedVariance]("getAddr.out")
  val getBlocksOut = newOutlet[GetBlocksMessage @uncheckedVariance]("getBlocks.out")
  val getDataOut = newOutlet[GetDataMessage @uncheckedVariance]("getData.out")
  val getHeadersOut = newOutlet[GetHeadersMessage @uncheckedVariance]("getHeaders.out")
  val headersOut = newOutlet[HeadersMessage @uncheckedVariance]("headers.out")
  val signedHeadersOut = newOutlet[SignedHeadersMessage @uncheckedVariance]("signedHeaders.out")
  val mempoolOut = newOutlet[MempoolMessage @uncheckedVariance]("mempool.out")
  val pingOut = newOutlet[PingMessage @uncheckedVariance]("ping.out")
  val pongOut = newOutlet[PongMessage @uncheckedVariance]("pong.out")
  val rejectOut = newOutlet[RejectMessage @uncheckedVariance]("reject.out")
  val verackOut = newOutlet[VerackMessage @uncheckedVariance]("verack.out")
  val versionOut = newOutlet[VersionMessage @uncheckedVariance]("version.out")
}

class ProtocolRoute extends GraphStage[ProtocolFanOutShape] {

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

    var inFlight = Map.empty[Outlet[_], BlockchainMessage]


    override def preStart(): Unit = tryPull(shape.in)
    def tryPulling = () => if (!hasBeenPulled(shape.in)) tryPull(shape.in)

    setHandler(shape.in, new InHandler {
      def doEmit[M <: BlockchainMessage](elem: M, out: Outlet[M]) = {
        if (isAvailable(out)) {
           emit(out, elem, tryPulling)
        } else {
          inFlight = inFlight + (out -> elem)
        }
      }

      override def onPush(): Unit = grab(shape.in) match {
        case m: DataMessage          => doEmit(m, shape.dataOut)
        case m: AddrMessage          => doEmit(m, shape.addrOut)
        case m: AlertMessage         => doEmit(m, shape.alertOut)
        case m: GetAddrMessage       => doEmit(m, shape.getAddrOut)
        case m: GetBlocksMessage     => doEmit(m, shape.getBlocksOut)
        case m: GetDataMessage       => doEmit(m, shape.getDataOut)
        case m: GetHeadersMessage    => doEmit(m, shape.getHeadersOut)
        case m: HeadersMessage       => doEmit(m, shape.headersOut)
        case m: SignedHeadersMessage => doEmit(m, shape.signedHeadersOut)
        case m: MempoolMessage       => doEmit(m, shape.mempoolOut)
        case m: PingMessage          => doEmit(m, shape.pingOut)
        case m: PongMessage          => doEmit(m, shape.pongOut)
        case m: RejectMessage        => doEmit(m, shape.rejectOut)
        case m: VerackMessage        => doEmit(m, shape.verackOut)
        case m: VersionMessage       => doEmit(m, shape.versionOut)
      }
    })
    for (o <- shape.outlets) {
      setHandler(o, new OutHandler {
        override def onPull(): Unit = {
          if (inFlight.contains(o)) {
            val elem = inFlight(o)
            inFlight = inFlight - o
            emit(o.as[Any], elem, tryPulling)
          } else tryPulling()
        }
      })
    }
  }

  override val shape = new ProtocolFanOutShape
}

