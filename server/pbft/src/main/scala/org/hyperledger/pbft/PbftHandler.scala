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
import org.hyperledger.common.{ Block, Header }
import org.hyperledger.pbft.BlockHandler.{ ConsensusTimeout, Start, Stop }
import org.hyperledger.pbft.Consensus.StartConsensus
import org.hyperledger.pbft.PbftHandler._
import org.hyperledger.pbft.PbftMiner.{ StartMining, StopMining }
import org.hyperledger.pbft.RecoveryActor.NewHeaders
import org.hyperledger.pbft.ViewChangeHandler.StoreTimeout

import scala.util.{ Failure, Success }

object PbftHandler {

  def props(broadcaster: ActorRef, miner: ActorRef) = Props(new PbftHandler(broadcaster, miner))

  sealed trait PbftHandlerState
  case object Normal extends PbftHandlerState
  case object ViewChangeOngoing extends PbftHandlerState

  case class PbftHandlerData(currentViewSeq: Int)

  sealed trait PbftHandlerMessage
  case class BroadcastPrepare(blockHeader: Header) extends PbftHandlerMessage
  case class BroadcastCommit(blockHeader: Header) extends PbftHandlerMessage
  case class BroadcastViewChange(viewSeq: Int) extends PbftHandlerMessage
  case class BroadcastNewView(viewSeq: Int, viewChanges: List[ViewChange], blockHeader: Header, commits: List[Commit]) extends PbftHandlerMessage
  case class BroadcastGetHeaders() extends PbftHandlerMessage
  case class BlockMined(block: Block) extends PbftHandlerMessage
  case class UpdateViewSeq(viewSeq: Int) extends PbftHandlerMessage
  case class NewViewAccepted(viewSeq: Int) extends PbftHandlerMessage
  case class ViewChangeLimitReached() extends PbftHandlerMessage

  val emptyResponse = List.empty[PbftMessage]

}

class PbftHandler(broadcaster: ActorRef, miner: ActorRef) extends LoggingFSM[PbftHandlerState, PbftHandlerData] {

  import context.dispatcher

  import scala.concurrent.duration._

  implicit val timeout = Timeout(1 seconds)

  val extension = PbftExtension(context.system)
  val store = extension.blockStoreConn
  val settings = extension.settings
  val ourNodeId = settings.ourNodeId
  val privateKey = settings.privateKey.get
  val numberOfNodes = settings.nodes.size

  val fRounded = roundUp((numberOfNodes - 1) / 3f)
  def roundUp(n: Float) = math.ceil(n).toInt
  def enough(n: Int) = n > 2 * fRounded
  def primary(node: Int, view: Int) = view % numberOfNodes == node

  val blockHandler = context.actorOf(BlockHandler.props(fRounded, self), "blockhandler")
  val viewChangeHandler = context.actorOf(ViewChangeHandler.props(fRounded), "viewchangehandler")

  if (primary(ourNodeId, 0)) startMining()

  startWith(Normal, PbftHandlerData(0))

  when(Normal) {
    normal orElse viewChangeOngoing
  }

  when(ViewChangeOngoing) {
    viewChangeOngoing
  }

  def normal: StateFunction = {
    case Event(PrePrepareMessage(m), data) =>
      val sdr = sender()
      store.hasBlock(m.block.getPreviousID).onComplete {
        case Success(false) =>
          log.debug(s"Unknown parent ${m.block.getPreviousID} in PrePrepare, recovery needed")
          sdr ! List(extension.getHeadersMessage)
        case Success(true) =>
          if (m.viewSeq == data.currentViewSeq) {
            if (primary(m.node, data.currentViewSeq)) {
              ask(blockHandler, m) pipeTo sdr
            } else {
              log.debug(s"PrePrepare arrived from non-primary node ${m.node} in currentViewSeq ${data.currentViewSeq}")
              sdr ! emptyResponse
            }
          } else {
            log.debug(s"PrePrepare with viewSeq ${m.viewSeq} not in currentViewSeq ${data.currentViewSeq}")
            sdr ! emptyResponse
          }
        case Failure(t) =>
          log.debug(s"Error when checking parent stored state: ${t.getMessage}")
          sdr ! emptyResponse
      }
      stay

    case Event(PrepareMessage(m), data) if m.viewSeq == data.currentViewSeq =>
      if (m.viewSeq == data.currentViewSeq) {
        ask(blockHandler, m) pipeTo sender
      } else {
        log.debug(s"Prepare with viewSeq ${m.viewSeq} not in currentViewSeq ${data.currentViewSeq}")
        sender ! emptyResponse
      }
      stay

    case Event(CommitMessage(m), data) if m.viewSeq == data.currentViewSeq =>
      if (m.viewSeq == data.currentViewSeq) {
        ask(blockHandler, m) pipeTo sender
      } else {
        log.debug(s"Commit with viewSeq ${m.viewSeq} not in currentViewSeq ${data.currentViewSeq}")
        sender ! emptyResponse
      }
      stay

    case Event(BlockMined(block), data) =>
      if (primary(ourNodeId, data.currentViewSeq)) {
        PrePrepare(ourNodeId, data.currentViewSeq, block).sign(privateKey).fold(
          err => log.error(s"Could not sign PrePrepare: ${err.message}"),
          signed => {
            broadcaster ! PrePrepareMessage(signed)
            blockHandler ! StartConsensus(block)
          })
      }
      stay

    case Event(BroadcastPrepare(blockHeader), data) =>
      Prepare(ourNodeId, data.currentViewSeq, blockHeader).sign(privateKey).fold(
        err => log.error(s"Could not sign Prepare: ${err.message}"),
        signed => {
          broadcaster ! PrepareMessage(signed)
          blockHandler ? signed
        })
      stay

    case Event(BroadcastCommit(blockHeader), data) =>
      Commit(ourNodeId, data.currentViewSeq, blockHeader).sign(privateKey).fold(
        err => log.error(s"Could not sign commit: ${err.message}"),
        signed => {
          broadcaster ! CommitMessage(signed)
          blockHandler ? signed
        })
      stay

    case Event(ConsensusTimeout(), data) =>
      viewChangeHandler ! StoreTimeout(data.currentViewSeq)
      goto(ViewChangeOngoing)

    case Event(UpdateViewSeq(viewSeq), data) =>
      if (viewSeq > data.currentViewSeq) {
        log.debug(s"ViewSeq updated to $viewSeq")
        switchMining(viewSeq)
        stay using data.copy(currentViewSeq = viewSeq)
      } else {
        stay
      }
  }

  def viewChangeOngoing: StateFunction = {
    case Event(ViewChangeMessage(m), data) =>
      viewChangeHandler ! m
      sender ! emptyResponse
      stay

    case Event(NewViewMessage(m: NewView), data) =>
      viewChangeHandler ! m
      sender ! emptyResponse
      stay

    case Event(NewViewAccepted(viewSeq), data) =>
      goto(Normal) using data.copy(currentViewSeq = viewSeq)

    case Event(ViewChangeLimitReached(), data) =>
      if (stateName == Normal) {
        blockHandler ! Stop()
        goto(ViewChangeOngoing)
      } else {
        stay
      }

    case Event(BroadcastGetHeaders(), data) =>
      broadcaster ! extension.getHeadersMessage
      stay

    case Event(m: NewHeaders, data) =>
      viewChangeHandler ! m
      stay

    case Event(BroadcastNewView(viewSeq, viewChanges, blockHeader, commits), data) =>
      NewView(ourNodeId, viewSeq, viewChanges, blockHeader, commits).sign(privateKey).fold(
        err => log.error(s"Could not sign NewView: ${err.message}"),
        signed => broadcaster ! NewViewMessage(signed))
      goto(Normal) using data.copy(currentViewSeq = viewSeq)

    case Event(BroadcastViewChange(viewSeq), data) =>
      val highest = store.getHighestBlock
      store.getCommits(highest.getID).flatMap { commits =>
        ViewChange(ourNodeId, viewSeq, highest.getHeader, commits).sign(privateKey)
      }.fold(
        err => log.debug(s"Failed to create ViewChange: ${err.message}"),
        msg => {
          broadcaster ! ViewChangeMessage(msg)
          viewChangeHandler ! msg
        })
      stay
  }

  whenUnhandled {
    case Event(m: PbftMessage, data) =>
      log.debug(s"Discard message: $m".take(160))
      sender ! emptyResponse
      stay
  }

  onTransition {
    case _ -> ViewChangeOngoing =>
      stopMining()

    case ViewChangeOngoing -> _ =>
      blockHandler ! Start()
      switchMining(nextStateData.currentViewSeq)
  }

  def switchMining(viewSeq: Int) =
    if (primary(ourNodeId, viewSeq)) startMining()
    else stopMining()

  def startMining() = {
    log.debug("Start mining")
    miner ! StartMining()
  }

  def stopMining() = {
    log.debug("Stop mining")
    miner ! StopMining()
  }

}
