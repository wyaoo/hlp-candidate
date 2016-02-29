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

import akka.actor.{ ActorRef, LoggingFSM, Props }
import org.hyperledger.common.{ BID, Block, Header }
import org.hyperledger.network.Rejection
import org.hyperledger.pbft.BlockHandler._
import org.hyperledger.pbft.Consensus.{ ConsensusBye, StartConsensus, Stored }
import org.hyperledger.pbft.PbftHandler.{ BroadcastCommit, BroadcastPrepare }

import scala.concurrent.Future
import scala.util.{ Failure, Success }

object BlockHandler {

  sealed trait BlockHandlerState
  case object Running extends BlockHandlerState
  case object Stopped extends BlockHandlerState

  case class BlockHandlerData(workers: Map[BID, ActorRef])

  sealed trait BlockHandlerMessage
  case class Start() extends BlockHandlerMessage
  case class Stop() extends BlockHandlerMessage
  case class ConsensusTimeout() extends BlockHandlerMessage
  case class ConsensusAction(id: BID, message: Any, initiator: ActorRef) extends BlockHandlerMessage
  case class SendPrepare(blockHeader: Header) extends BlockHandlerMessage
  case class SendCommit(blockHeader: Header) extends BlockHandlerMessage
  case class Store(block: Block, commits: List[Commit]) extends BlockHandlerMessage

  def props(f: Int, parent: ActorRef) = Props(new BlockHandler(f, parent))

  val timerName = "no-store"

  def rejectResponse(msg: String, id: BID): List[PbftMessage] =
    List(RejectMessage(Rejection.invalidBlock(msg)(id)))

}

class BlockHandler(f: Int, parent: ActorRef) extends LoggingFSM[BlockHandlerState, BlockHandlerData] {

  import context.dispatcher
  import PbftHandler.emptyResponse

  val extension = PbftExtension(context.system)
  val settings = extension.settings
  val store = extension.blockStoreConn

  startWith(Running, BlockHandlerData(Map.empty))

  setTimer(timerName, ConsensusTimeout(), settings.protocolTimeout)

  when(Running) {
    case Event(m: PrePrepare, data) =>
      val s = sender()

      validate(m.block).onComplete {
        case Success(true) =>
          self ! ConsensusAction(m.block.getID, m, s)
        case Success(false) =>
          s ! rejectResponse("Block not accepted", m.block.getID)
        case Failure(t) =>
          log.debug(s"Error during block validation: ${t.getMessage}")
          s ! emptyResponse
      }

      stay()

    case Event(m: StartConsensus, data) =>
      val id = m.block.getID
      val worker = getOrCreateWorker(id)
      worker ! m
      stay() using data.copy(workers = data.workers.updated(id, worker))

    case Event(m: Prepare, data) =>
      sendIn(m.blockHeader.getID, m, sender)
      stay()

    case Event(m: Commit, data) =>
      sendIn(m.blockHeader.getID, m, sender)
      stay()

    case Event(ConsensusAction(id, m, s), data) =>
      val worker = getOrCreateWorker(id)
      worker ! m
      s ! emptyResponse
      stay using data.copy(workers = data.workers.updated(id, worker))

    case Event(SendPrepare(blockHeader), data) =>
      parent ! BroadcastPrepare(blockHeader)
      stay()

    case Event(SendCommit(blockHeader), data) =>
      parent ! BroadcastCommit(blockHeader)
      stay()

    case Event(Store(block, commits), data) =>
      store.store(block, commits).onComplete {
        case Success(true) =>
          setTimer(timerName, ConsensusTimeout(), settings.protocolTimeout)
          data.workers(block.getID) ! Stored()
        case Success(false) => log.debug("Failed to store")
        case Failure(t)     => log.debug(s"Failed to store: ${t.getMessage}")
      }
      stay()

    case Event(ConsensusBye(id), data) =>
      stay() using data.copy(workers = data.workers - id)

    case Event(m: ConsensusTimeout, data) =>
      data.workers.values.foreach(context.stop)
      cancelTimer(timerName)
      parent ! m
      goto(Stopped) using data.copy(workers = Map.empty)

    case Event(Stop(), data) =>
      data.workers.values.foreach(context.stop)
      cancelTimer(timerName)
      goto(Stopped) using data.copy(workers = Map.empty)

  }

  when(Stopped) {
    case Event(Start(), data) =>
      setTimer(timerName, ConsensusTimeout(), settings.protocolTimeout)
      goto(Running)

    // format: OFF
    case Event(m @ (_: PrePrepare |
                    _: StartConsensus |
                    _: Prepare |
                    _: Commit |
                    _: ConsensusAction |
                    _: SendPrepare |
                    _: SendCommit |
                    _: Store), data) =>
      // format: ON
      log.debug(s"Discarded message $m".take(160))
      stay()
  }

  def getOrCreateWorker(id: BID): ActorRef = stateData.workers.get(id) match {
    case Some(worker) => worker
    case None         => context.actorOf(Consensus.props(f, id, self), s"consensus-$id")
  }

  def has(blockID: BID) = store.hasBlock(blockID) map { result =>
    if (result) log.debug(s"Block already accepted: $blockID")
    result
  }

  def validate(block: Block): Future[Boolean] = has(block.getID) flatMap {
    case true  => Future.successful(false)
    case false => store.validateBlock(block)
  }

  def sendIn[M](id: BID, msg: M, sdr: ActorRef) = has(id).onComplete {
    case Success(false) =>
      self ! ConsensusAction(id, msg, sdr)
    case Success(true) =>
      sdr ! emptyResponse
    case Failure(t) =>
      log.debug(s"Error during block existance check: ${t.getMessage}")
      sdr ! emptyResponse
  }

}
