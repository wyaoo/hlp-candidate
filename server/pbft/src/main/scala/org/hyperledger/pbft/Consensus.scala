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
import org.hyperledger.common.{ BID, Block }
import org.hyperledger.pbft.Consensus._

class Consensus(f: Int, blockId: BID, parent: ActorRef) extends LoggingFSM[ConsensusState, ConsensusData] {

  import PbftHandler._

  val settings = PbftExtension(context.system).settings

  startWith(WaitForPrePrepare, ConsensusData(None, Set.empty, Set.empty))

  when(WaitForPrePrepare) {
    case Event(PrePrepare(_, viewSeq, block, _), data) =>
      parent ! SendPrepare(viewSeq, block.getHeader)
      data.prepares.foreach { self ! _ }
      goto(WaitForPrepare) using ConsensusData(Some(block), Set.empty, data.commits)

    case Event(StartConsensus(block), data) =>
      data.prepares.foreach { self ! _ }
      goto(WaitForPrepare) using data.copy(block = Some(block))

    case Event(m: Prepare, data) =>
      stay() using data.copy(prepares = data.prepares + m)

    case Event(m: Commit, data) =>
      stay() using data.copy(commits = data.commits + m)
  }

  when(WaitForPrepare) {
    case Event(m: Prepare, data) =>
      val prepares = data.prepares + m
      if (prepares.size >= 2 * f) {
        parent ! SendCommit(m.viewSeq, data.block.get.getHeader)
        data.commits.foreach { self ! _ }
        goto(WaitForCommit) using ConsensusData(data.block, prepares, Set.empty)
      } else {
        stay() using data.copy(prepares = prepares)
      }

    case Event(m: Commit, data) =>
      stay() using data.copy(commits = data.commits + m)
  }

  when(WaitForCommit) {
    case Event(m: Commit, data) =>
      val commits = data.commits + m
      if (commits.size > 2 * f) {
        parent ! Store(data.block.get, commits.toList)
        goto(Done)
      } else {
        stay() using data.copy(commits = data.commits + m)
      }
  }

  when(Done) {
    case x =>
      log.debug(s"arrived after done: $x")
      stay()
  }

  whenUnhandled {
    case Event(PbftTimeout(), _) =>
      parent ! ConsensusTimeout()
      goto(Done)
  }

  onTransition {
    case WaitForPrePrepare -> _ =>
      setTimer("timeout", PbftTimeout(), settings.protocolTimeout)

    case _ -> Done =>
      parent ! Bye(blockId)
  }

}

object Consensus {
  sealed trait ConsensusState
  case object WaitForPrePrepare extends ConsensusState
  case object WaitForPrepare extends ConsensusState
  case object WaitForCommit extends ConsensusState
  case object Done extends ConsensusState

  case class ConsensusData(block: Option[Block], prepares: Set[Prepare], commits: Set[Commit])

  sealed case class PbftTimeout()

  def props(f: Int, blockId: BID, parent: ActorRef): Props = Props(new Consensus(f, blockId, parent))
}
