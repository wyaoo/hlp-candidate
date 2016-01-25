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
package org.hyperledger.core.bitcoin;

import org.hyperledger.common.BID;
import org.hyperledger.common.HyperLedgerException;
import org.hyperledger.common.TID;
import org.hyperledger.core.BlockStore;

public interface BitcoinBlockStore extends BlockStore {
    boolean isBlockVersionUsed(int minimumVersion, BID until, int required, int window);

    int medianBlockTime(int window);

    int getTransactionHeight(TID h) throws HyperLedgerException;
}
