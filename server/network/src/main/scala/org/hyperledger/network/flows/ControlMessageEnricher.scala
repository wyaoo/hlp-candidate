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

import akka.actor.ActorRef
import akka.stream.stage._
import org.hyperledger.network.Version
import org.hyperledger.network.server.ServerActor.PeerControlMessage
import org.slf4j.LoggerFactory

/**
 *
 */
class ControlMessageEnricher extends StatefulStage[ControlMessage, PeerControlMessage] {
  val LOG = LoggerFactory.getLogger(classOf[ControlMessageEnricher])

  // not sure is it a good idea, but I don't know how to know in the StageStates that we are terminated due to an error
  var ERROR: Option[Throwable] = None

  override def initial: StageState[ControlMessage, PeerControlMessage] = new Waiting

//  override def onUpstreamFinish(ctx: Context[PeerControlMessage]): TerminationDirective = {
//    ctx.absorbTermination()
//  }
//
//  override def onUpstreamFailure(cause: Throwable, ctx: Context[PeerControlMessage]): TerminationDirective = {
//    ERROR = Some(cause)
//    ctx.absorbTermination()
//  }

  class Waiting extends StageState[ControlMessage, PeerControlMessage] {
    def onPush(elem: ControlMessage, ctx: Context[PeerControlMessage]): SyncDirective =
      if (ctx.isFinishing) ctx.finish()
      else elem match {
        case ProtocolFlowMaterialized(controlSource) =>
          become(new Uninit(controlSource))
          ctx.pull()
        case cm =>
          LOG.warn(s"Unexpected control message $cm")
          ctx.pull()
      }
  }

  class Uninit(controlSource: ActorRef) extends StageState[ControlMessage, PeerControlMessage] {
    def onPush(elem: ControlMessage, ctx: Context[PeerControlMessage]): SyncDirective = {
      if (ctx.isFinishing) ctx.finish()
      else elem match {
        case hc @ HandshakeComplete(v) =>
          emit(Iterator(PeerControlMessage(hc, v, controlSource)), ctx, new Handshake(controlSource, v))
        case cm =>
          LOG.warn(s"Unexpected control message $cm")
          ctx.pull()
      }
    }
  }
  class Handshake(controlSource: ActorRef, v: Version) extends StageState[ControlMessage, PeerControlMessage] {

    def ctrMsg(controlMessage: ControlMessage) = PeerControlMessage(controlMessage, v, controlSource)

    override def onPull(ctx: Context[PeerControlMessage]): SyncDirective = {
      if (ctx.isFinishing) {
        // TODO clean up error handling of peers. Now the connection actor also sends completion events
        // Also, peer disconnecting isn't an error but represented as an exception
        val msg = ctrMsg(ERROR.map(e => PeerCommunicationFailure(e)).getOrElse(PeerDisconnected))
        ctx.pushAndFinish(msg)
      }  else super.onPull(ctx)
    }

    override def onPush(elem: ControlMessage, ctx: Context[PeerControlMessage]): SyncDirective = {
      emit(Iterator(ctrMsg(elem)), ctx)
    }
  }
}
