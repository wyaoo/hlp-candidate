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

import java.net.Socket
import org.hyperledger.network.Messages
import Messages.{RejectMessage, VersionMessage, BlockchainMessage}
import org.hyperledger.network.Messages
import org.hyperledger.stresstest.helper.{MessageAssert, Channel}
import Channel._
import MessageAssert.{assertHasMessage, assertHasNoMessages}
import org.scalatest.{FeatureSpec, GivenWhenThen}
import scodec.Codec

import scala.collection.mutable.ListBuffer

class MultipleHandshakeAttempts extends FeatureSpec with GivenWhenThen {


  feature("Handshake") {
    scenario("Successful handshake") {
      Given("connected to the server")
      val (socket: Socket, codec: Codec[BlockchainMessage]) = connectToServer

      When("sending a version message")
      val replyMessages = sendVersionMessage(socket, codec)

      Then("the reply contains one version message")
      assertHasMessage("Reply should have one version message", replyMessages, m => m.isInstanceOf[VersionMessage], allowOtherExtraMessages = true)
    }

    scenario("A few handshake attempts") {
      Given("connected to the server")
      val (socket: Socket, codec: Codec[BlockchainMessage]) = connectToServer

      Given("sending a version message")
      sendVersionMessage(socket, codec)

      val retryCount = 100
      When(s"sending $retryCount more version messages")
      val replyMessageBuffer = new ListBuffer[BlockchainMessage]
      for (i <- 1 to retryCount) {
        replyMessageBuffer.appendAll(sendVersionMessage(socket, codec))
      }

      Then(s"reply contains $retryCount reject messages")
      assertHasMessage(s"Expected $retryCount reject messages as replies", replyMessageBuffer.toList, m => m.isInstanceOf[RejectMessage], retryCount)
    }

    scenario("More handshake attempts") {
      Given("connected to the server")
      val (socket: Socket, codec: Codec[BlockchainMessage]) = connectToServer

      Given("sending a version message")
      sendVersionMessage(socket, codec)

      val retryCount = 100
      Given(s"sending $retryCount more version messages")
      val replyMessageBuffer = new ListBuffer[BlockchainMessage]
      for (i <- 1 to retryCount) {
        replyMessageBuffer.appendAll(sendVersionMessage(socket, codec))
      }

      When(s"sending even $retryCount more version messages")
      replyMessageBuffer.clear()
      for (i <- 1 to retryCount) {
        replyMessageBuffer.appendAll(sendVersionMessage(socket, codec))
      }

      Then("no nore replies are received")
      assertHasNoMessages("Three should be no more replies", replyMessageBuffer.toList)
    }
  }
}
