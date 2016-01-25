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
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.ByteString
import org.hyperledger.core.bitcoin.GenesisBlocks
import org.hyperledger.network.Messages._
import org.hyperledger.network.{Messages, Ping}
import org.hyperledger.scodec.interop.akka._
import org.scalatest.{FunSpec, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration._

class ScodecStageTest extends FunSpec with Matchers {

  val codec = Messages.messageCodec(1)
  implicit val system = ActorSystem("test")
  implicit val mat = ActorMaterializer()

  val decodeFlow = Flow[ByteString].transform(() => ScodecStage.decode(Messages.messageCodec(1)))

  describe("The PbftMessageParser stages") {
    describe("when receiving a lot of bytes") {
      it("should emit all messages") {

        val msgs = List(
          VerackMessage(),
          PingMessage(Ping(1)),
          BlockMessage(GenesisBlocks.regtest),
          PongMessage(Ping(1)))

        val bits = msgs.map(codec.encode).flatMap(_.require.bytes.grouped(2).map(_.toByteString))

        val future = Source(bits).via(decodeFlow).runWith(Sink.fold(Seq.empty[BlockchainMessage])(_ :+ _))
        val result = Await.result(future, 1000.millis)
        result shouldBe msgs
      }
    }
  }
}
