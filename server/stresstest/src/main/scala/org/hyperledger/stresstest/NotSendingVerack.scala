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

import org.hyperledger.network.Messages
import Messages.{PongMessage, BlockchainMessage, VerackMessage}
import org.hyperledger.network.Messages
import org.hyperledger.stresstest.helper.Channel._
import org.hyperledger.stresstest.helper.MessageAssert._
import org.scalatest.{FeatureSpec, GivenWhenThen}


class NotSendingVerack extends FeatureSpec with GivenWhenThen {

  feature("No verack") {
    scenario("Not sending Verack message") {
      Given("connected to the server")
      val (socket, codec, replyMessages) = handshakeWithServer

      Given("the reply contains one verack message")
      assertHasMessage("Reply should have one verack message", replyMessages, m => m.isInstanceOf[VerackMessage], allowOtherExtraMessages = true)

      When(s"not replying to verack")
      // nothing to do here

      var replyMessages2: List[BlockchainMessage] = List.empty
      val timeoutMillis = 5000L
      val thresholdMillis = 1000L
      Then(s"the server disconnects after about ${timeoutMillis / 1000} seconds")
      Thread.sleep(timeoutMillis - thresholdMillis)
      replyMessages2 = sendPingMessage(socket, codec)
      assertHasMessage(s"The server disconnected too early, before about ${timeoutMillis / 1000} seconds", replyMessages2, m => m.isInstanceOf[PongMessage])
      Thread.sleep(thresholdMillis)
      replyMessages2 = sendPingMessage(socket, codec)
      assertHasNoMessages(s"The server did not disconnect after about ${timeoutMillis / 1000} seconds", replyMessages2)
    }
  }
}
