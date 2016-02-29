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

package org.hyperledger.stresstest

import java.net.{ InetAddress, InetSocketAddress }
import java.util.concurrent.Executors

import akka.actor.ActorSystem
import akka.stream.scaladsl.Flow
import akka.stream.stage.{ GraphStageLogic, GraphStageWithMaterializedValue, InHandler }
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }
import com.codahale.metrics.MetricRegistry
import nl.grons.metrics.scala.{ FutureMetrics, InstrumentedBuilder }
import org.hyperledger.common.BID
import org.hyperledger.network.BlockDataRequest
import org.hyperledger.network.Messages.{ BlockchainMessage, GetBlocksMessage }
import org.hyperledger.stresstest.helper.{ ServerProperties, StressTestNode }

import scala.concurrent.{ Await, ExecutionContext, Future, Promise }
import scala.util.Success


object BlocksAttempt2 extends App {
  
}


object BlocksTestStuff {// extends App {
  val metricRegistry = new MetricRegistry
  val requestPool = Executors.newFixedThreadPool(10)
  implicit val as = ActorSystem()
  import scala.concurrent.duration._
  implicit val ec = ExecutionContext.fromExecutor(requestPool)

  val address = new InetSocketAddress(InetAddress.getByName(ServerProperties.regtestHost), ServerProperties.regtestPort)

  def zeroblockTask = StressTestNode.connectToPeer(address, Flow.fromGraph(new ZeroHashBlockStage))

  val qq = (1 to 60).map(_ => Instrumented.timing[Unit]("ZeroHashBlockRequest")(zeroblockTask._2))
  val t = Future.sequence(qq)
  try {
    Await.ready(t, 30 seconds)
    val timer = Instrumented.metricRegistry.timer("ZeroHashBlockRequest")
    println(s"${timer.getCount} ${timer.getOneMinuteRate} ${timer.getMeanRate}")
  } finally {
    as.shutdown()
    as.awaitTermination(10 seconds)
  }
  requestPool.shutdown()
  println(s"foo futures: ${t.isCompleted}, system: ${as.isTerminated}")
}

object Instrumented extends InstrumentedBuilder with FutureMetrics {
  val metricRegistry = new MetricRegistry
}

class ZeroHashBlockStage extends GraphStageWithMaterializedValue[FlowShape[BlockchainMessage, BlockchainMessage], Future[Unit]] {
  val in = Inlet[BlockchainMessage]("in")
  val out = Outlet[BlockchainMessage]("out")

  val shape = FlowShape(in, out)
  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[Unit]) = {
    val responsePromise = Promise[Unit]
    val logic = new GraphStageLogic(shape) {

      override def preStart(): Unit = {
        emit(out, GetBlocksMessage(BlockDataRequest(70002L, List(BID.INVALID), BID.INVALID)))
        tryPull(in)
      }
      setHandler(out, eagerTerminateOutput)
      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val _ = grab(in)
          responsePromise.tryComplete(Success(()))
          completeStage()
        }
      })
    }
    (logic, responsePromise.future)
  }

}
