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
package org.hyperledger.pbft.streams

import akka.stream.stage.{ SyncDirective, Context, PushStage }
import org.hyperledger.pbft._

class VerifySignatureStage(settings: PbftSettings) extends PushStage[PbftMessage, PbftMessage] {

  case class InvalidSignatureException(message: PbftMessage) extends RuntimeException(s"Invalid signature: $message".take(160))

  val getKey = settings.nodes.map(_.publicKey).lift

  override def onPush(elem: PbftMessage, ctx: Context[PbftMessage]): SyncDirective = elem.payload match {
    case p: PrePrepare if !p.verify(getKey) => ctx.fail(InvalidSignatureException(elem))
    case p: Prepare if !p.verify(getKey)    => ctx.fail(InvalidSignatureException(elem))
    case p: Commit if !p.verify(getKey)     => ctx.fail(InvalidSignatureException(elem))
    case p: ViewChange if !p.verify(getKey) => ctx.fail(InvalidSignatureException(elem))
    case p: NewView if !p.verify(getKey)    => ctx.fail(InvalidSignatureException(elem))
    case _                                  => ctx.push(elem)
  }
}
