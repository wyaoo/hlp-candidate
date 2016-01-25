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
package org.hyperledger.stresstest.helper

import org.hyperledger.network.Messages
import Messages.BlockchainMessage
import org.hyperledger.network.Messages
import org.scalatest.Assertions._

object MessageAssert {

  def assertHasNoMessages(message: String, messages: List[BlockchainMessage]): Unit = {
    assert(messages.isEmpty, message)
  }

  def assertHasMessage(message: String,
                       messages: List[BlockchainMessage],
                       expectedMessageType: (BlockchainMessage) => Boolean,
                       expectedCount: Int = 1,
                       allowOtherExtraMessages: Boolean = false): Unit = {
    val matchingMessageCount = messages.count(m => expectedMessageType(m))
    val otherExtraMessageCount = messages.length - matchingMessageCount
    val isOtherExtraAcceptedIfExists = if (allowOtherExtraMessages) otherExtraMessageCount >= 0 else otherExtraMessageCount == 0

    assert(matchingMessageCount == expectedCount && isOtherExtraAcceptedIfExists, message)
  }

}
