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

import org.hyperledger.stresstest.helper.Channel._
import org.scalatest.{FeatureSpec, GivenWhenThen}

class Oversized extends FeatureSpec with GivenWhenThen {

  feature("Oversized message") {
    scenario("Sending oversized message") {
      Given("connected to the server")
      val (socket, codec, _) = handshakeWithServer

      When("sending an oversized request bigger than 2 MB")
      // getHeaders message with 65535 hashes would exceed the 2MB message size limit
      // with 65534 hashes its size is 2097127 which is 25 bytes lesss than 2MB
      val (locatorHashes, stopHash) = getManyHashes(socket, codec, 65535)
      val replies = sendGetHeadersMessage(locatorHashes, stopHash)(socket, codec)

      Then("the server does not reply")
      assert(replies.isEmpty, "The server must not have replied")
    }
  }
}

