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

import akka.actor._
import akka.pattern.{ ask, pipe }
import akka.util.Timeout
import org.hyperledger.common.BID
import org.hyperledger.core.BlockStore.BlockListener
import org.hyperledger.core.BlockStoredInfo
import org.hyperledger.pbft.CommitCounter.Finished
import org.hyperledger.pbft.PbftClient._
import org.hyperledger.pbft.PbftHandler.UpdateViewSeq
import org.hyperledger.pbft.RecoveryActor.NewHeaders

import scala.collection.JavaConversions._
import scala.concurrent.Future
import scala.util.{ Failure, Success }

object PbftClient {
  sealed trait PbftClientState
  case object Running extends PbftClientState

  case class PbftClientData(counters: Map[BID, Option[ActorRef]])

  case class HandleNewBlock(id: BID, msg: Commit, sdr: ActorRef)
  case class BlockAdded(id: List[BID])

  def props() = Props(new PbftClient)

  val emptyResponse = List.empty[PbftMessage]
}

class PbftClient extends LoggingFSM[PbftClientState, PbftClientData] {

  import context.dispatcher
  import scala.concurrent.duration._

  implicit val timeout = Timeout(1 seconds)

  val extension = PbftExtension(context.system)
  val store = extension.blockStoreConn
  val numberOfNodes = extension.settings.nodes.size
  val fRounded = roundUp((numberOfNodes - 1) / 3f)
  def roundUp(n: Float) = math.ceil(n).toInt

  val listener = new BlockListener {
    override def blockStored(content: BlockStoredInfo): Unit = self ! BlockAdded(content.getAddedToTrunk.toList)
  }

  store.addBlockListener(listener)

  startWith(Running, PbftClientData(Map.empty))

  when(Running) {
    case Event(CommitMessage(m), data) =>
      val id = m.blockHeader.getID
      val s = sender
      store.hasBlock(id).onComplete {
        case Success(false) =>
          self ! HandleNewBlock(id, m, s)
        case Success(true) =>
          log.debug(s"Block $id already stored")
          s ! emptyResponse
        case Failure(t) =>
          log.error(s"Failed to check block $id existance: ${t.getMessage}")
          s ! emptyResponse
      }
      stay

    case Event(HandleNewBlock(id, msg, sdr), data) =>
      val handler = data.counters.getOrElse(id, Some(context.actorOf(CommitCounter.props(id, fRounded))))
      handler match {
        case Some(h) =>
          (h ? msg) pipeTo sdr
        case None =>
          log.debug(s"Block $id already on its way")
          sdr ! emptyResponse
      }
      val counters = data.counters.updated(id, handler)
      stay using data.copy(counters = counters)

    case Event(m: PbftMessage, data) =>
      log.debug(s"PBFT message discarded: $m".take(160))
      sender ! emptyResponse
      stay

    case Event(Finished(id), data) =>
      val counters = data.counters.updated(id, None)
      stay using data.copy(counters = counters)

    case Event(BlockAdded(id), data) =>
      val counters = data.counters -- id
      log.debug(s"Removing handler for $id")
      stay using data.copy(counters = counters)
  }

  whenUnhandled {
    case Event(m @ (_:UpdateViewSeq | _:NewHeaders), data) =>
      log.debug(s"Message discarded: $m".take(160))
      stay
  }

  onTermination {
    case StopEvent(_, _, _) =>
      store.removeBlockListener(listener)
  }

}
