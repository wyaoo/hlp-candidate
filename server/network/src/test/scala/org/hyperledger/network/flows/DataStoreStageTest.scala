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

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Keep, Flow }
import akka.stream.testkit.scaladsl.{ TestSink, TestSource }
import akka.testkit.{ ImplicitSender, TestKit }
import org.hyperledger.network.{Messages, HyperLedger}
import Messages._

import org.scalatest.FunSpecLike
import org.scalatest.mock.MockitoSugar

import scala.concurrent.Future
import org.mockito.Mockito._

class DataStoreStageTest extends TestKit(ActorSystem()) with FunSpecLike with ImplicitSender with MockitoSugar {
  implicit val materializer = ActorMaterializer()

  implicit val ec = system.dispatcher

  val hyperLedger = mock[HyperLedger]
  when(hyperLedger.filterUnknown(Nil)).thenReturn(Future.successful(Nil))

  val dataMessageFlow = Flow.fromGraph(new DataStoreStage(hyperLedger))

  describe("DataStoreStage") {
    it("should work") {
      val (pub, sub) = TestSource.probe[CtrlOrDataMessage]
        .via(dataMessageFlow)
        .toMat(TestSink.probe[CtrlOrMessage])(Keep.both)
        .run()

      sub.request(n = 3)
      pub.sendNext(Right(InvMessage.txs(Nil)))
      sub.expectNext(NoReply)
    }
  }

}
