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
import org.hyperledger.pbft.PbftHandler._
import org.hyperledger.pbft.RecoveryActor.{ NewHeaders, RecoverTo }
import org.hyperledger.pbft.ViewChangeHandler._

import scala.util.{ Failure, Success }
import scalaz._

object ViewChangeHandler {
  sealed trait ViewChangeState
  case object CountDuringNormal extends ViewChangeState
  case object CountDuringViewChange extends ViewChangeState
  case object Recovery extends ViewChangeState

  // format: OFF
  case class ViewChandeData(viewChanges: Map[Int, ViewChange],
                            targetViewSeq: Int,
                            recoveryActor: Option[ActorRef])
  // format: ON

  sealed trait ViewChangeHandlerMessage
  case class StoreTimeout(viewSeq: Int) extends ViewChangeHandlerMessage
  case class RecoveredBeforeNewView() extends ViewChangeHandlerMessage
  case class RecoveredAfterNewView() extends ViewChangeHandlerMessage
  case class RecoveryStarted(recoveryActor: ActorRef) extends ViewChangeHandlerMessage
  case class RecoveryFailed() extends ViewChangeHandlerMessage
  case class NoNewView(count: Int) extends ViewChangeHandlerMessage

  val timerName = "no-new-view"

  def props(f: Int) = Props(new ViewChangeHandler(f))
}

class ViewChangeHandler(f: Int) extends LoggingFSM[ViewChangeState, ViewChandeData] {

  import SignatureValidator._
  import context.dispatcher

  val extension = PbftExtension(context.system)
  val settings = extension.settings
  val store = extension.blockStoreConn

  def primary(viewSeq: Int) = viewSeq % settings.nodes.size == settings.ourNodeId

  startWith(CountDuringNormal, ViewChandeData(Map.empty, 0, None))

  when(CountDuringNormal) {
    case Event(m @ ViewChange(node, viewSeq, _, commits, _), data) =>
      forEnoughGoodCommits(commits) {
        val viewChanges = data.viewChanges.updated(node, m)
        if (viewChanges.size > f) {
          val minViewSeq = viewChanges.values.map(_.viewSeq).min
          sender ! ViewChangeLimitReached()
          sender ! BroadcastViewChange(minViewSeq)
          goto(CountDuringViewChange) using data.copy(viewChanges = viewChanges, targetViewSeq = minViewSeq)
        } else {
          stay using data.copy(viewChanges = viewChanges)
        }
      }

    case Event(StoreTimeout(viewSeq), data) =>
      val nextViewSeq = viewSeq + 1
      sender ! BroadcastViewChange(nextViewSeq)
      goto(CountDuringViewChange) using data.copy(targetViewSeq = nextViewSeq)
  }

  when(CountDuringViewChange) {
    case Event(m @ ViewChange(node, viewSeq, _, commits, _), data) =>
      forEnoughGoodCommits(commits) {
        val viewChanges = data.viewChanges.updated(node, m)
        val viewChangesWithMostViewSeq = viewChanges.values.groupBy(_.viewSeq).maxBy(_._2.size)
        val targetViewSeq = viewChangesWithMostViewSeq._1
        val usedViewChanges = viewChangesWithMostViewSeq._2
        if (primary(targetViewSeq) && usedViewChanges.size > 2 * f) {
          val recoveryActor = context.actorOf(RecoveryActor.props(f, RecoveredBeforeNewView()))
          sender ! BroadcastGetHeaders()
          goto(Recovery) using
            data.copy(viewChanges = viewChanges, targetViewSeq = targetViewSeq, recoveryActor = Some(recoveryActor))
        } else {
          log.debug(s"ViewChange limit not fullfilled yet in viewSeq $targetViewSeq, size: ${usedViewChanges.size}")
          stay using data.copy(viewChanges = viewChanges)
        }
      }

    case Event(NoNewView(count), data) =>
      val nextCount = count + 1
      setTimer(timerName, NoNewView(nextCount), settings.protocolTimeout * nextCount)
      val nextViewSeq = data.targetViewSeq + 1
      context.parent ! BroadcastViewChange(nextViewSeq)
      stay using data.copy(targetViewSeq = nextViewSeq)
  }

  when(Recovery) {
    case Event(RecoveredBeforeNewView(), data) =>
      val viewChanges = data.viewChanges.values.filter(_.viewSeq == data.targetViewSeq).toList
      val blockHeader = store.getHighestBlock.getHeader
      store.getCommits(blockHeader.getID).fold(
        err => log.error(s"Failed to get commits for ${blockHeader.getID}"),
        commits => {
          context.parent ! BroadcastNewView(data.targetViewSeq, viewChanges, blockHeader, commits)
        })
      goto(CountDuringNormal) using data.copy(viewChanges = Map.empty, recoveryActor = None)

    case Event(RecoveredAfterNewView(), data) =>
      context.parent ! NewViewAccepted(data.targetViewSeq)
      goto(CountDuringNormal) using data.copy(viewChanges = Map.empty, recoveryActor = None)

    case Event(RecoveryStarted(recoveryActor), data) =>
      data.recoveryActor.foreach(context.stop)
      stay using data.copy(recoveryActor = Some(recoveryActor))

    case Event(RecoveryFailed(), data) =>
      goto(CountDuringViewChange)

    case Event(m: ViewChange, data) =>
      log.debug(s"ViewChange from node ${m.node} discarded, already during ViewChange (Recovery)")
      stay

    case Event(StoreTimeout(_), data) =>
      log.debug("Store timeout discarded, already in ViewChange (Recovery)")
      stay
  }

  whenUnhandled {
    case Event(NewView(_, viewSeq, viewChanges, blockHeader, commits, _), data) =>
      forEnoughGoodViewChanges(viewChanges) {
        forEnoughGoodCommits(commits) {
          store.hasBlock(blockHeader.getID).onComplete {
            case Success(true) =>
              self ! RecoveredAfterNewView()
            case Success(false) =>
              val recoveryActor = context.actorOf(RecoveryActor.props(f, RecoveredAfterNewView()))
              self ! RecoveryStarted(recoveryActor)
              recoveryActor ! RecoverTo(blockHeader.getID)
              context.parent ! BroadcastGetHeaders()
            case Failure(t) =>
              log.error(s"Failed to check block existance: ${t.getMessage}")
              self ! RecoveryFailed()
          }
          goto(Recovery) using data.copy(targetViewSeq = viewSeq)
        }
      }

    case Event(m: NewHeaders, data) =>
      data.recoveryActor foreach { _ ! m }
      stay

    case Event(StoreTimeout(_), data) =>
      log.debug("Store timeout discarded, already in ViewChange")
      stay
  }

  onTransition {
    case _ -> CountDuringViewChange =>
      setTimer(timerName, NoNewView(1), settings.protocolTimeout)

    case CountDuringViewChange -> _ =>
      cancelTimer(timerName)
  }

  def forEnoughGoodCommits(commits: List[Commit])(func: => State): State = forEnoughGood(commits, validateCommits, func)
  def forEnoughGoodViewChanges(viewChanges: List[ViewChange])(func: => State): State = forEnoughGood(viewChanges, validateViewChanges, func)

  type ValidatorFunc[T] = List[T] => Reader[PbftSettings, List[MessageOrErr[T]]]
  def forEnoughGood[T](messages: List[T], validate: ValidatorFunc[T], func: => State): State = {
    val (bad, good) = split(validate(messages).run(settings))
    bad.foreach { item => log.debug(s"Message ${item._2} failed validation: ${item._1}") }
    if (good.size > 2 * f) {
      func
    } else {
      log.debug(s"Message discarded because it failed signature validation")
      stay
    }
  }

}
