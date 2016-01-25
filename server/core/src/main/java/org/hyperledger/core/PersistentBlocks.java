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
package org.hyperledger.core;

import org.hyperledger.common.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface PersistentBlocks {
    void start();

    StoredTransaction readTransaction(TID hash) throws HyperLedgerException;

    StoredTransaction readTransaction(Outpoint outpoint) throws HyperLedgerException;

    StoredHeader readHeader(BID hash) throws HyperLedgerException;

    StoredBlock readBlock(BID hash) throws HyperLedgerException;

    byte[] readMisc(BID id);

    PersistenceStatistics writeBlock(StoredBlock b) throws HyperLedgerException;

    boolean writeMisc(BID id, byte[] data);

    void readHeaders(Map<BID, StoredHeader> headers) throws HyperLedgerException;

    boolean isEmpty();

    boolean hasTransaction(TID hash) throws HyperLedgerException;

    List<TID> readBlockTIDList(BID hash) throws HyperLedgerException;

    boolean hasBlock(BID hash) throws HyperLedgerException;

    Set<StoredTransaction> getTransactionsWithOutput(Script script) throws HyperLedgerException;

    boolean probablyHadTransactionsWithOutput(Script script) throws HyperLedgerException;

    Set<StoredTransaction> getSpendingTransactions(Outpoint outpoint) throws HyperLedgerException;

    void updateBlock(StoredBlock prunedBlock) throws HyperLedgerException;
}
