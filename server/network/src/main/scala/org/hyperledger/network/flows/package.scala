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
package org.hyperledger.network

import akka.stream._
import Messages.{DataMessage, BlockchainMessage}

package object flows {

  type MessageFlowShape[M <: BlockchainMessage] = FlowShape[M, CtrlOrMessage]
  type MessageFlow[M <: BlockchainMessage] = Graph[MessageFlowShape[M], Unit]

  type CtrlOrDataMessage = Either[ControlMessage, DataMessage]

}
