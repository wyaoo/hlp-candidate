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

import java.nio.charset.Charset

import org.hyperledger.common.{Transaction, Hash, BID, TID}
import scala.collection.JavaConverters._


object Implicits {
  implicit class HashOPS(h: Hash) {
    def toBID = BID.createFromSafeArray(h.unsafeGetArray())
    def toTID = TID.createFromSafeArray(h.unsafeGetArray())
  }

  implicit val charset = Charset.forName("UTF-8")

  implicit class TxExtra(tx: Transaction) {
    def sourceInventory = tx.getInputs.asScala
      .map(_.getSourceTransactionID)
      .map(InventoryVector.tx)
      .toList.distinct

  }
}
