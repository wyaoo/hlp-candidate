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
package org.hyperledger.pbft

import akka.actor.{ LoggingFSM, Props }
import org.hyperledger.common.BID
import org.hyperledger.core.BlockStore.BlockListener
import org.hyperledger.core.BlockStoredInfo
import org.hyperledger.pbft.RecoveryActor._

import scala.util.{ Failure, Success }

object RecoveryActor {

  sealed trait RecoveryState
  case object WaitForHeaders extends RecoveryState
  case object WaitForBlock extends RecoveryState

  case class RecoveryData(n: Int)

  sealed trait RecoveryMessage
  case class NewHeaders() extends RecoveryMessage
  case class RecoverTo(aim: BID) extends RecoveryMessage
  case class RecoveryComplete() extends RecoveryMessage

  def props(f: Int, readyMsg: Any) = Props(new RecoveryActor(f, readyMsg))

}

class RecoveryActor(f: Int, readyMsg: Any) extends LoggingFSM[RecoveryState, RecoveryData] {

  import context.dispatcher

  val extension = PbftExtension(context.system)
  val store = extension.blockStoreConn
  val settings = extension.settings

  startWith(WaitForHeaders, RecoveryData(1))

  when(WaitForHeaders, stateTimeout = settings.protocolTimeout) {
    case Event(NewHeaders(), data) =>
      val n = data.n + 1
      if (n > 2 * f) {
        recoverTo(store.getHighestHeader.getID)
        goto(WaitForBlock)
      } else {
        stay using data.copy(n = n)
      }

    case Event(RecoverTo(aim), _) =>
      recoverTo(aim)
      goto(WaitForBlock)

    case Event(StateTimeout, _) =>
      goto(WaitForBlock)

  }

  when(WaitForBlock) {
    case Event(_: RecoveryComplete, _) =>
      context.parent ! readyMsg
      stop()
  }

  whenUnhandled {
    case Event(NewHeaders(), _) =>
      log.debug("Late NewHeaders")
      stay()
  }

  def listener(aim: BID) = new BlockListener {
    override def blockStored(content: BlockStoredInfo): Unit =
      if (content.getAddedToTrunk.contains(aim)) {
        self ! RecoveryComplete()
        store.removeBlockListener(this)
      }
  }

  def recoverTo(aim: BID): Unit = store.hasBlock(aim).onComplete {
    case Success(true) =>
      self ! RecoveryComplete()
      log.debug("Already has the newest block")
    case Success(false) =>
      store.addBlockListener(listener(aim))
      log.debug(s"Listener added for recovery aim: $aim")
    case Failure(t) =>
      log.error(s"Failure in hasBlock: ${t.getMessage}")
  }

}
