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

import akka.stream.stage._
import org.hyperledger.network.{Version, Messages, HyperLedgerExtension}
import Messages._

/**
 * Processing stage for outgoing connection.
 * - First handles the handshake phase, not letting through any other message
 * - After the handshake is successfull, it enriches incoming MessagePayloads by
 *   creating Incoming messages
 *
 */
class HandshakeStage(hyperLedger: HyperLedgerExtension,
                     ourVersion: Option[Version] = None) extends StatefulStage[Incoming[_ <: BlockchainMessage], CtrlOrMessage] {

  def versionP = hyperLedger.versionPredicate _

  def vesionReply = (ourVersion.map(VersionMessage).toList :+ VerackMessage()).map(Reply(_)).iterator

  class InitialState extends StageState[Incoming[_ <: BlockchainMessage], CtrlOrMessage] {
    def onPush(m: Incoming[_ <: BlockchainMessage], ctx: Context[CtrlOrMessage]) = {
      m match {
        case Incoming(_: VerackMessage)                 => emit(Iterator.empty, ctx, new AckedState)
        case Incoming(VersionMessage(v)) if versionP(v) => emit(vesionReply, ctx, new VersionReceivedState(v))
        case Incoming(VersionMessage(v))                => ctx.fail(ProtocolError.incompatible(v))
        case Incoming(msg)                              => ctx.fail(ProtocolError.invalidMessage(msg))
      }
    }
  }

  class AckedState extends StageState[Incoming[_ <: BlockchainMessage], CtrlOrMessage] {
    def onPush(m: Incoming[_ <: BlockchainMessage], ctx: Context[CtrlOrMessage]) = m match {
      case Incoming(VersionMessage(v)) if versionP(v) => emit(completeMessages(v, Iterator(Reply(VerackMessage()))), ctx, new HandshakeCompleteState(v))
      case Incoming(VersionMessage(v))                => ctx.fail(ProtocolError.incompatible(v))
      case Incoming(msg)                              => ctx.fail(ProtocolError.invalidMessage(msg))
    }
  }

  class VersionReceivedState(v: Version) extends StageState[Incoming[_ <: BlockchainMessage], CtrlOrMessage] {
    def onPush(m: Incoming[_ <: BlockchainMessage], ctx: Context[CtrlOrMessage]) = m match {
      case Incoming(_: VerackMessage) => emit(completeMessages(v), ctx, new HandshakeCompleteState(v))
      case Incoming(msg)              => ctx.fail(ProtocolError.invalidMessage(msg))
    }
  }

  class HandshakeCompleteState(version: Version) extends StageState[Incoming[_ <: BlockchainMessage], CtrlOrMessage] {
    def onPush(m: Incoming[_ <: BlockchainMessage], ctx: Context[CtrlOrMessage]) = m match {
      case Incoming(msg: VersionMessage) => ctx.fail(ProtocolError.invalidMessage(msg))
      case Incoming(msg: VerackMessage)  => ctx.fail(ProtocolError.invalidMessage(msg))
      case msg                           => ctx.push(msg)
    }
  }

  private def completeMessages(v: Version, fix: Iterator[CtrlOrMessage] = Iterator.empty) = {
    fix ++ List(HandshakeComplete(v), Reply(hyperLedger.createGetHeadersRequest))

  }

  override def initial = new InitialState
}

