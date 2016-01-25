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

import akka.actor.{ Actor, ActorRef, LoggingFSM, Props }
import org.hyperledger.common.{ BID, Block, Header }
import org.hyperledger.network.{InventoryVector, InventoryVectorType}
import InventoryVectorType.MSG_BLOCK
import org.hyperledger.network.Rejection
import org.hyperledger.pbft.PbftHandler._
import org.hyperledger.pbft.PbftMiner.{StopMining, StartMining}
import scodec.Attempt

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.{ Failure, Success }

object PbftHandler {
  def props(broadcaster: ActorRef, miner: ActorRef) = Props(new PbftHandler(broadcaster, miner))

  sealed trait PbftHandlerState
  case object Normal extends PbftHandlerState
  case object ViewChangeOngoing extends PbftHandlerState

  case class PbftHandlerData(activeConsensus: Map[BID, ActorRef],
    activeViewChanges: Map[Int, ActorRef],
    currentViewSeq: Int,
    pendingViewChanges: List[ViewChange]) {

    def getOrCreateConsensusWorker(id: BID, f: Int, parent: Actor): (Map[BID, ActorRef], ActorRef) = {
      activeConsensus.get(id) match {
        case Some(worker) => (activeConsensus, worker)
        case None =>
          val worker = parent.context.actorOf(Consensus.props(f, id, parent.self))
          (activeConsensus.updated(id, worker), worker)
      }
    }

    def getOrCreateViewChangeWorker(viewSeq: Int, f: Int, parent: Actor): (Map[Int, ActorRef], ActorRef) = {
      activeViewChanges.get(viewSeq) match {
        case Some(worker) => (activeViewChanges, worker)
        case None =>
          val worker = parent.context.actorOf(ViewChangeWorker.props(f, viewSeq, parent.self), s"pbft-viewchange-$viewSeq")
          (activeViewChanges.updated(viewSeq, worker), worker)
      }
    }

  }

  sealed trait PbftHandlerMessage
  case class SendPrepare(viewSeq: Int, blockHeader: Header) extends PbftHandlerMessage
  case class SendCommit(viewSeq: Int, blockHeader: Header) extends PbftHandlerMessage
  case class SendNewView(viewSeq: Int, viewChanges: List[ViewChange], blockHeader: Header, commits: List[Commit]) extends PbftHandlerMessage
  case class Store(block: Block, commits: List[Commit]) extends PbftHandlerMessage
  case class ConsensusTimeout() extends PbftHandlerMessage
  case class Bye(id: BID) extends PbftHandlerMessage
  case class ViewChangeBye(viewSeq: Int) extends PbftHandlerMessage
  case class RecoveredToTop(viewSeq: Int, newViewSender: ActorRef) extends PbftHandlerMessage
  case class NoNewView(duration: FiniteDuration) extends PbftHandlerMessage
  case class BlockMined(block: Block) extends PbftHandlerMessage
  case class ConsensusAction(id: BID, m: Any) extends PbftHandlerMessage
  case class StartConsensus(block: Block) extends PbftHandlerMessage

}

class PbftHandler(broadcaster: ActorRef, miner: ActorRef)
  extends LoggingFSM[PbftHandlerState, PbftHandlerData] {

  import SignatureValidator._
  import context.dispatcher

  val extension = PbftExtension(context.system)
  val store = extension.blockStoreConn
  val settings = extension.settings
  val ourNodeId = settings.ourNodeId
  val privateKey = settings.privateKey
  val numberOfNodes = settings.nodes.size

  val fRounded = roundUp((numberOfNodes - 1) / 3f)
  def roundUp(n: Float) = math.ceil(n).toInt
  def enough(n: Int) = n > 2 * fRounded
  def primary(node: Int, view: Int) = view % numberOfNodes == node

  if(primary(ourNodeId, 0))
    miner ! StartMining()

  startWith(Normal, PbftHandlerData(Map.empty, Map.empty, 0, List.empty))

  when(Normal) {
    receiveExternalMessage orElse
      sendExternalMessage orElse
      receiveInternalMessage orElse
      viewChangeOngoing
  }

  when(ViewChangeOngoing) {
    viewChangeOngoing
  }

  def receiveExternalMessage: StateFunction = {
    case Event(m @ PrePrepareMessage(PrePrepare(node, viewSeq, block, _)), data) if viewSeq == data.currentViewSeq =>
      if (primary(node, data.currentViewSeq)) {
        log.debug(s"Received PrePrepare from the primary ($node) viewSeq: $viewSeq")

        val sdr = sender()

        store.validateBlock(block).onComplete {
          case Success(true) =>
            consensusActionForNewBlock(block.getID, m.payload).onComplete { result =>
              sendBackNothing(sdr)
            }
          case Success(false) =>
            sendBackReject(sdr, Rejection.invalidBlock("Block not accepted")(block.getID))
          case Failure(t) =>
            log.debug(s"Block not accepted: ${t.getMessage}")
            sendBackReject(sdr, Rejection.invalidBlock("Block not accepted")(block.getID))
        }

      } else {
        log.debug("PrePrepare not sent by primary.")
      }

      stay()


    case Event(PrepareMessage(m), data) if m.viewSeq == data.currentViewSeq =>
      val sdr = sender()
      consensusActionForNewBlock(m.blockHeader.getID, m).onComplete { result =>
        sendBackNothing(sdr)
      }
      stay()

    case Event(CommitMessage(m), data) if m.viewSeq == data.currentViewSeq =>
      val sdr = sender()
      consensusActionForNewBlock(m.blockHeader.getID, m).onComplete { result =>
        sendBackNothing(sdr)
      }
      stay()

    case Event(BlockMined(block), data) =>
      if (primary(ourNodeId, data.currentViewSeq)) {
        PrePrepare(ourNodeId, data.currentViewSeq, block).sign(privateKey).fold(
          err => log.error(s"Could not sign PrePrepare: ${err.message}"),
          signed => {
            broadcaster ! PrePrepareMessage(signed)
            consensusActionForNewBlock(block.getID, StartConsensus(block))
          }
        )
      } else {
        broadcaster ! InvMessage(List(InventoryVector(MSG_BLOCK, block.getID)))
      }
      stay()

    case Event(BlockMessage(block), data) =>
      log.debug(s"Received block ${block.getID}")
      if (primary(ourNodeId, data.currentViewSeq)) {
        val sdr = sender()

        store.validateBlock(block).onComplete {
          case Success(true) =>
            PrePrepare(ourNodeId, data.currentViewSeq, block).sign(privateKey).fold(
              err => {
                log.error(s"Could not sign PrePrepare: ${err.message}")
                sendBackNothing(sdr)
              },
              signed => {
                broadcaster ! PrePrepareMessage(signed)
                consensusActionForNewBlock(block.getID, StartConsensus(block)).onComplete { result =>
                  sendBackNothing(sdr)
                }
              })
          case Success(false) =>
            sendBackReject(sdr, Rejection.invalidBlock("Block not accepted")(block.getID))
          case Failure(t) =>
            log.debug(s"Error during block validation: ${t.getMessage}")
            sendBackNothing(sdr)
        }

      }
      stay()
  }

  def sendExternalMessage: StateFunction = {
    case Event(SendPrepare(viewSeq, blockHeader), data) =>
      Prepare(ourNodeId, viewSeq, blockHeader).sign(privateKey).fold(
        err => log.error(s"Could not sign Prepare: ${err.message}"),
        signed => {
          broadcaster ! PrepareMessage(signed)
          data.activeConsensus(blockHeader.getID) ! signed
        })
      stay()

    case Event(SendCommit(viewSeq, blockHeader), data) =>
      Commit(ourNodeId, viewSeq, blockHeader).sign(privateKey).fold(
        err => log.error(s"Could not sign commit: ${err.message}"),
        signed => {
          broadcaster ! CommitMessage(signed)
          data.activeConsensus(blockHeader.getID) ! signed
        })
      stay()
  }

  def receiveInternalMessage: StateFunction = {
    case Event(ConsensusAction(id, m), data) =>
      val (workers, worker) = data.getOrCreateConsensusWorker(id, fRounded, this)
      worker ! m
      stay() using data.copy(activeConsensus = workers)

    case Event(Store(block, commits), data) =>
      store.store(block, commits).onComplete {
        case Success(true)  =>
          cancelTimer("no-store")
          setTimer("no-store", ConsensusTimeout(), settings.protocolTimeout)
        case Success(false) => log.debug("Failed to store commits")
        case Failure(err)   => log.debug(s"Failed to store block or commits: ${err.getMessage}")
      }
      stay()

    case Event(ConsensusTimeout(), data) =>
      data.activeConsensus.values.foreach(context.stop)
      val currentViewSeq = data.currentViewSeq + 1
      createSignedViewChange(currentViewSeq) match {
        case Attempt.Successful(message) =>
          broadcaster ! ViewChangeMessage(message)
          if (primary(ourNodeId, currentViewSeq)) {
            val (workers, worker) = data.getOrCreateViewChangeWorker(currentViewSeq, fRounded, this)
            worker ! message
            goto(ViewChangeOngoing) using PbftHandlerData(Map.empty, workers, currentViewSeq, data.pendingViewChanges)
          } else {
            goto(ViewChangeOngoing) using data.copy(activeConsensus = Map.empty, currentViewSeq = currentViewSeq)
          }
        case Attempt.Failure(err) =>
          log.debug(s"Could not create ViewChange: ${err.message}")
          goto(ViewChangeOngoing) using data.copy(activeConsensus = Map.empty, currentViewSeq = currentViewSeq)
      }
  }

  def viewChangeOngoing: StateFunction = {
    case Event(ViewChangeMessage(m), data) if primary(ourNodeId, m.viewSeq) =>
      val pendingViewChanges = data.pendingViewChanges :+ m
      val (workers, worker) = data.getOrCreateViewChangeWorker(m.viewSeq, fRounded, this)
      worker ! m
      if (stateName == Normal && data.pendingViewChanges.size > fRounded) {
        val viewSeqs = pendingViewChanges.map(_.viewSeq)
        val currentViewSeq = data.currentViewSeq + 1
        val minViewSeq = (viewSeqs :+ currentViewSeq).min
        createSignedViewChange(minViewSeq).fold(
          err => log.debug(s"Failed to create ViewChange: ${err.message}"),
          m => broadcaster ! ViewChangeMessage(m))
        sendBackNothing(sender())
        goto(ViewChangeOngoing) using
          data.copy(activeViewChanges = workers, currentViewSeq = minViewSeq, pendingViewChanges = pendingViewChanges)
      } else {
        sendBackNothing(sender())
        stay() using data.copy(activeViewChanges = workers, pendingViewChanges = pendingViewChanges)
      }
    // TODO should ask for the block if unknown

    case Event(NewViewMessage(NewView(_, viewSeq, viewChanges, blockHeader, commits, _)), data) =>
      val (badVC, goodVC) = split(validateViewChanges(viewChanges).run(settings))
      val sdr = sender()

      badVC.foreach { item => log.debug(s"Message ${item._2} failed validation: ${item._1}") }

      if (enough(goodVC.size)) {
        val (badC, goodC) = split(validateCommits(commits).run(settings))

        badC.foreach { item => log.debug(s"Message ${item._2} failed validation: ${item._1}") }

        if (enough(goodC.size)) {
          def success(): Unit = {
            log.debug("Going back to normal")
            self ! RecoveredToTop(viewSeq, sdr)
            switchMining(viewSeq)
          }
          store.recoverTo(blockHeader.getID, broadcaster, success).onComplete {
            case Success(msg)   =>log.debug(s"Recovery finished: $msg")
            case Failure(err) =>
              log.debug(s"Failed to start recovery: ${err.getMessage}")
              sendBackNothing(sdr)
          }
        } else {
          log.debug("Discarded NewView, not enough valid Commit signature")
          sendBackNothing(sdr)
        }
      } else {
        log.debug("Discarded NewView, not enough valid ViewChange signature")
        sendBackNothing(sdr)
      }
      stay() using data.copy(pendingViewChanges = data.pendingViewChanges.filter(_.viewSeq != viewSeq))

    case Event(RecoveredToTop(viewSeq, sdr), data) =>
      data.activeConsensus.values.foreach(context.stop)
      sendBackNothing(sdr)
      goto(Normal) using data.copy(currentViewSeq = viewSeq, activeConsensus = Map.empty)

    case Event(SendNewView(viewSeq, viewChanges, blockHeader, commits), data) =>
      NewView(ourNodeId, viewSeq, viewChanges, blockHeader, commits).sign(privateKey).fold(
        err => log.error(s"Could not sign NewView: ${err.message}"),
        signed => {
          broadcaster ! NewViewMessage(signed)
          switchMining(viewSeq)
        })
      data.activeConsensus.values.foreach(context.stop)
      goto(Normal) using data.copy(currentViewSeq = viewSeq,
        activeConsensus = Map.empty,
        pendingViewChanges = data.pendingViewChanges.filter(_.viewSeq != viewSeq))

    case Event(NoNewView(duration), data) =>
      val viewSeq = data.currentViewSeq + 1
      createSignedViewChange(viewSeq).fold(
        err => log.debug(s"Could not create ViewChange: ${err.message}"),
        message => broadcaster ! ViewChangeMessage(message))
      val newDuration = duration * 2
      setTimer("no new view", NoNewView(newDuration), newDuration)
      stay() using data.copy(currentViewSeq = viewSeq)

  }

  whenUnhandled {
    case Event(Bye(id), data) =>
      data.activeConsensus.get(id).foreach(context.stop)
      stay() using data.copy(activeConsensus = data.activeConsensus - id)

    case Event(ViewChangeBye(viewSeq), data) =>
      data.activeViewChanges.get(viewSeq).foreach(context.stop)
      stay() using data.copy(activeViewChanges = data.activeViewChanges - viewSeq)

    case Event(m: GetBlocksMessage, data) =>
      broadcaster ! m
      stay()

    case Event(m: PbftMessage, data) =>
      sendBackNothing(sender())
      stay()
  }

  onTransition {
    case _ -> ViewChangeOngoing =>
      val duration = settings.protocolTimeout
      setTimer("no new view", NoNewView(duration), duration)

    case ViewChangeOngoing -> _ =>
      cancelTimer("no new view")
  }

  def switchMining(viewSeq: Int) =
    if (primary(ourNodeId, viewSeq)) {
      log.debug("Start mining")
      miner ! StartMining()
    }
    else {
      log.debug("Stop mining")
      miner ! StopMining()
    }

  def consensusActionForNewBlock(id: BID, m: Any) =
    store.hasBlock(id).flatMap {
      case true =>
        log.debug("Block already accepted")
        Future.successful(())
      case false =>
        self ! ConsensusAction(id, m)
        Future.successful(())
    }

  def sendBackNothing(sdr: ActorRef) =
    sdr ! List.empty[PbftMessage]

  def sendBackReject(sdr: ActorRef, reject: Rejection) =
    sdr ! List(RejectMessage(reject))

  def createSignedViewChange(viewSeq: Int): Attempt[ViewChange] = {
    val highest = store.getHighestBlock
    store.getCommits(highest.getID)
      .flatMap { commits => ViewChange(ourNodeId, viewSeq, highest.getHeader, commits).sign(privateKey) }
  }

}
