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

import scala.util.control.NoStackTrace

class BlockchainException(msg: String) extends RuntimeException(msg) with NoStackTrace
abstract class InvalidVersionException extends BlockchainException("invalid version")
case object InvalidVersionException extends InvalidVersionException

case class RejectionException(msg: String, rejection: Rejection) extends BlockchainException(msg)
