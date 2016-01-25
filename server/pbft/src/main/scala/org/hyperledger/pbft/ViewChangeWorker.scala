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
import org.hyperledger.pbft.ViewChangeWorker._

object ViewChangeWorker {
  sealed trait ViewChangeState
  case object ViewChangeCounting extends ViewChangeState
  case object Recovery extends ViewChangeState
  case object Done extends ViewChangeState

  sealed case class Recovered()
  sealed case class ViewChangeTimeout()

  def props(f: Int, viewSeq: Int, parent: ActorRef): Props =
    Props(new ViewChangeWorker(f, viewSeq, parent))
}

class ViewChangeWorker(f: Int, viewSeq: Int, parent: ActorRef)
  extends LoggingFSM[ViewChangeState, Set[ViewChange]] {

  import PbftHandler._
  import context.dispatcher
  import SignatureValidator._

  val extension = PbftExtension(context.system)
  val settings = extension.settings
  val store = extension.blockStoreConn

  startWith(ViewChangeCounting, Set.empty)

  setTimer("timeout", ViewChangeTimeout(), settings.protocolTimeout)

  when(ViewChangeCounting) {
    case Event(m @ ViewChange(_, _, blockHeader, commits, _), messagesSoFar) =>
      val (bad, good) = split(validateCommits(commits).run(settings))
      bad.foreach { item => log.debug(s"Message ${item._2} failed validation: ${item._1}") }
      if (good.size > 2 * f) {
        val messages = messagesSoFar + m
        if (messages.size > 2 * f) {
          store.recover(parent).onComplete( result => { val self = context.self; self ! Recovered() })
          goto(Recovery) using messages
        } else {
          stay() using messages
        }
      } else {
        log.debug("ViewChange discarded because Commits failed signature validation")
        stay()
      }
  }

  when(Recovery) {
    case Event(Recovered(), messages) =>
      val highest = store.getHighestBlock
      store.getCommits(highest.getID).fold(
        err => log.error(s"Failed to get commits: ${err.message}"),
        commits => parent ! SendNewView(viewSeq, messages.toList, highest.getHeader, commits)
      )
      goto(Done)

  }

  when(Done) {
    case x =>
      log.debug(s"arrived after done: $x")
      stay()
  }

  whenUnhandled {
    case Event(ViewChangeTimeout(), _) =>
      goto(Done)
  }

  onTransition {
    case _ -> Done => parent ! ViewChangeBye(viewSeq)
  }

}

