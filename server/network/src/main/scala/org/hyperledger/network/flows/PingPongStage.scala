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

package org.hyperledger.network.flows

import java.time.temporal.ChronoUnit.MILLIS
import java.time.{ Clock, Instant }
import java.util.concurrent.TimeUnit.MILLISECONDS

import akka.stream._
import akka.stream.stage._
import org.hyperledger.network.Messages.{ BlockchainMessage, PingMessage, PongMessage }
import org.hyperledger.network.Ping
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.util.Random

object PingPongStage {
  case object SendPing
  case object CheckPing

  case class PingPongSettings(pingInterval: FiniteDuration = 2 minutes, timeout: FiniteDuration = 20 minutes)

  val LOG = LoggerFactory.getLogger(classOf[PingPongStage])

  def apply(monitor: FiniteDuration => Unit,
    settings: PingPongStage.PingPongSettings = PingPongStage.PingPongSettings(),
    clock: Clock = Clock.systemUTC()) =
    new PingPongStage(monitor, settings, clock)
}

class PingPongStage(latencyMonitor: FiniteDuration => Unit, settings: PingPongStage.PingPongSettings, clock: Clock)
  extends GraphStage[FlowShape[BlockchainMessage, BlockchainMessage]] {
  import PingPongStage._

  val in = Inlet[BlockchainMessage]("in")
  val out = Outlet[BlockchainMessage]("out")

  override def createLogic(inheritedAttributes: Attributes) = new TimerGraphStageLogic(shape) {
    var lastPing: Option[(Instant, Long)] = None

    override def preStart() = tryPull(in)

    override protected def onTimer(timerKey: Any): Unit = {
      timerKey match {
        case SendPing if lastPing.isEmpty =>
          val nonce = Random.nextLong()
          val instant = clock.instant()
          lastPing = Some((instant, nonce))
          scheduleOnce(CheckPing, settings.timeout)

          LOG.debug(s"Emiting Ping($nonce)")
          emit(out, PingMessage(Ping(nonce)))

        case CheckPing if lastPing.nonEmpty =>
          lastPing.map(p => pingTime(p._1)).filter(_ > settings.timeout) foreach { pingTime =>
            LOG.info(s"Ping timeout $pingTime")
            completeStage()
          }
        case m => //
      }
    }

    def pingTime(start: Instant) = Duration(MILLIS.between(start, clock.instant()), MILLISECONDS)

    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        grab(in) match {
          case PingMessage(ping) => emit(out, PongMessage(ping), () => tryPull(in))
          case PongMessage(Ping(nonce)) =>
            lastPing match {
              case Some((pingStart, ping)) if ping == nonce =>
                lastPing = None
                latencyMonitor(pingTime(pingStart))
              case Some((pingStart, ping)) => LOG.info("Nonce mismatch")
              case None                    => LOG.info("Unsolicited pong without ping")
            }
            tryPull(in)
          case m => tryPull(in)
        }
      }
    })

    // sends the first ping
    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        onTimer(SendPing)
        setHandler(out, eagerTerminateOutput)
        schedulePeriodically(SendPing, settings.pingInterval)
      }
    })
  }

  override val shape = FlowShape(in, out)
}
