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

import akka.actor.{ Actor, ActorLogging, ActorRef, Props }
import org.hyperledger.common.Block
import org.hyperledger.pbft.MinerWorker._
import org.hyperledger.pbft.PbftHandler.BlockMined
import org.hyperledger.pbft.PbftMiner.{ StartMining, StopMining }

object PbftMiner {
  def props() = Props(new PbftMiner())

  sealed trait PbftMinerMessage
  case class StartMining() extends PbftMinerMessage
  case class StopMining() extends PbftMinerMessage
}

class PbftMiner() extends Actor with ActorLogging {
  import context.dispatcher

  val worker = context.system.actorOf(Props[MinerWorker].withDispatcher("hyperledger.pbft.miner-dispatcher"))
  var requester: Option[ActorRef] = None
  val settings = PbftExtension(context.system).settings

  def scheduleMining() = context.system.scheduler.scheduleOnce(settings.blockFrequency, worker, Mine())

  override def receive = {

    case StartMining() =>
      requester = Some(sender())
      scheduleMining()

    case StopMining() =>
      requester = None

    case Mined(block) =>
      requester match {
        case Some(r) =>
          block.foreach { r ! BlockMined(_) }
          scheduleMining()
        case None =>
          log.debug("Mined block discarded, mining stopped after scheduling")
      }

  }
}

object MinerWorker {
  sealed case class Mine()
  sealed case class Mined(block: Option[Block])
}

class MinerWorker extends Actor with ActorLogging {

  val miner = PbftExtension(context.system).hyperLedgerCore.hyperLedger.getMiner

  override def receive = {
    case Mine() =>
      val block = miner.flatMap { m => Option(m.mineOneBlock()) }
      sender() ! Mined(block)
  }

}
