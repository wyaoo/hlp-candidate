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
import java.util.Set;

public interface BlockStore {

    List<ValidatedTransaction> getDescendants(List<TID> tids) throws HyperLedgerException;

    List<ValidatedTransaction> getSpendingTransactions(List<TID> tids) throws HyperLedgerException;

    interface TrunkFilter {
        boolean contains(BID h);
    }

    void start() throws HyperLedgerException;

    void stop();

    boolean isEmpty() throws HyperLedgerException;

    void addGenesis(Block b) throws HyperLedgerException;

    void rejectedByPeer(String command, Hash hash, int code);

    BID getSpvTop();

    BID getFullTop();

    StoredBlock getHighestBlock() throws HyperLedgerException;

    StoredHeader getHighestHeader() throws HyperLedgerException;

    StoredHeader getHeader(BID hash);

    StoredBlock getBlock(BID hash) throws HyperLedgerException;

    boolean hasTransaction(TID hash) throws HyperLedgerException;

    ValidatedTransaction getTransaction(TID hash) throws HyperLedgerException;

    BID getTrunkBlockID(Transaction t);

    List<BID> getMissingBlocks(int max);

    byte[] getMiscData(BID id);

    ValidatedTransaction addTransaction(Transaction t) throws HyperLedgerException;

    HeaderStoredInfo addHeader(Header b) throws HyperLedgerException;

    boolean hasBlock(BID h) throws HyperLedgerException;

    BlockStoredInfo addBlock(Block block) throws HyperLedgerException;

    boolean validateBlock(final Block block) throws HyperLedgerException;

    ValidatedTransaction addClientTransaction(Transaction t) throws HyperLedgerException;

    BlockStoredInfo addClientBlock(Block block) throws HyperLedgerException;

    boolean addMiscData(BID id, byte[] data) throws HyperLedgerException;

    void standalonePruneBlock(BID id, int toHeight) throws HyperLedgerException;

    int getSpvHeight();

    int getFullHeight();

    List<BID> getHeaderLocator();

    List<BID> catchUpHeaders(List<BID> inventory, int limit) throws HyperLedgerException;

    List<BID> catchUpBlocks(List<BID> inventory, int limit) throws HyperLedgerException;

    List<StoredTransaction> filterTransactions(Set<ByteVector> matchSet, MasterKey ek, int lookAhead)
            throws HyperLedgerException;

    List<ValidatedTransaction> getMempoolContent();

    List<ValidatedTransaction> scanUnconfirmedPool(Set<ByteVector> matchSet);

    List<StoredTransaction> filterTransactions(Set<ByteVector> matchSet)
            throws HyperLedgerException;

    void addBlockListener(BlockListener listener);

    void removeBlockListener(BlockListener listener);

    interface BlockListener {
        void blockStored(BlockStoredInfo content);
    }

}
