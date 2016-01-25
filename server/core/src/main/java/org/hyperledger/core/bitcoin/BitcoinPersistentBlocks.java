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

import com.google.common.base.Stopwatch;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import org.hyperledger.common.*;
import org.hyperledger.core.*;
import org.hyperledger.core.kvstore.OrderedMapStore;
import org.hyperledger.core.kvstore.OrderedMapStoreKey;
import org.hyperledger.model.LevelDBStore;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * blocks stored persistently.
 */
public class BitcoinPersistentBlocks implements PersistentBlocks {
    protected final OrderedMapStore store;

    @Override
    public void start() {
        store.open();
    }

    public BitcoinPersistentBlocks(OrderedMapStore store) {
        this.store = store;
    }

    private volatile int nRead = 0;
    private volatile long readTime = 0L;

    @Override
    public StoredTransaction readTransaction(TID hash) throws HyperLedgerException {
        nRead++;
        Stopwatch watch = Stopwatch.createStarted();
        try {
            byte[] data = store.get(OrderedMapStoreKey.createKey(OrderedMapStoreKey.KeyType.TX, hash.unsafeGetArray()));
            if (data != null) {
                StoredTransaction t = StoredTransaction.fromLevelDB(data);
                if (!t.getID().equals(hash)) {
                    throw new HyperLedgerException("Database inconsistency in TX " + hash);
                }
                return t;
            }
            return null;
        } finally {
            readTime += watch.stop().elapsed(TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public StoredTransaction readTransaction(Outpoint outpoint) throws HyperLedgerException {
        StoredTransaction transaction = readTransaction(outpoint.getTransactionId());
        if (transaction != null && transaction.getOutputs().size() > outpoint.getOutputIndex())
            return transaction;
        return null;
    }

    @Override
    public StoredHeader readHeader(BID hash) throws HyperLedgerException {
        byte[] data = store.get(OrderedMapStoreKey.createKey(OrderedMapStoreKey.KeyType.BLOCKHEADER, hash.unsafeGetArray()));
        if (data != null) {
            StoredHeader h = StoredHeader.fromLevelDB(data);
            if (!h.getID().equals(hash))
                throw new HyperLedgerException("Database hash inconsistency in block" + hash);
            return h;
        }
        return null;
    }

    @Override
    public StoredBlock readBlock(BID hash) throws HyperLedgerException {
        StoredHeader header = readHeader(hash);
        if (header != null) {
            byte[] content = store.get(OrderedMapStoreKey.createKey(OrderedMapStoreKey.KeyType.BLOCKCONTENT, hash.unsafeGetArray()));
            LevelDBStore.BLOCKCONTENT c;
            try {
                c = LevelDBStore.BLOCKCONTENT.parseFrom(content);
                List<MerkleTreeNode> txs = new ArrayList<>(c.getTxHashesCount());
                for (ByteString txhash : c.getTxHashesList()) {
                    byte[] rtxh = txhash.toByteArray();
                    Hash txh = Hash.createFromSafeArray(Arrays.copyOf(rtxh, 32));
                    if (rtxh[32] == 0) {
                        StoredTransaction tx = readTransaction(new TID(txh));
                        if (tx == null)
                            txs.add(new PrunedNode(txh, 0));
                        else
                            txs.add(tx);
                    } else {
                        txs.add(new PrunedNode(txh, rtxh[32] & 0xFF));
                    }
                }
                return new StoredBlock(header, txs);
            } catch (InvalidProtocolBufferException e) {
                throw new HyperLedgerException(e);
            }
        }
        return null;
    }

    private void writeTx(StoredTransaction t, BID block) throws HyperLedgerException {
        byte[] id = t.getID().unsafeGetArray();
        byte[] shortId = Arrays.copyOf(id, 4);
        t.addBlock(block);
        store.put(OrderedMapStoreKey.createKey(OrderedMapStoreKey.KeyType.TX, id), t.toLevelDB());
        for (TransactionOutput out : t.getOutputs()) {
            addDataIndex(OrderedMapStoreKey.KeyType.OUTSCRIPT, toBytesArray(out.getScript().hashCode()), shortId);
        }
        for (TransactionInput in : t.getInputs()) {
            if (!in.getSourceTransactionID().equals(TID.INVALID)) {
                addSpendIndex(in.getSource(), t.getID());
            }
        }
    }

    private void removeTransaction(TID tid) throws HyperLedgerException {
        store.remove(OrderedMapStoreKey.createKey(OrderedMapStoreKey.KeyType.TX, tid.unsafeGetArray()));
        StoredTransaction transaction = readTransaction(tid);
        if (transaction != null) {
            for (Coin c : transaction.getCoins()) {
                store.remove(
                        OrderedMapStoreKey.createKey(OrderedMapStoreKey.KeyType.SPEND, toBytesArray(c.getOutpoint().hashCode())));
            }
        }
    }

    @Override
    public void updateBlock(StoredBlock prunedBlock) throws HyperLedgerException {
        try {
            store.startBatch();

            for (TID tId : readBlockTIDList(prunedBlock.getID())) {
                if (!contained(prunedBlock.getTransactions(), tId)) {
                    removeTransaction(tId);
                }
            }
            store.put(OrderedMapStoreKey.createKey(OrderedMapStoreKey.KeyType.BLOCKCONTENT,
                    prunedBlock.getID().unsafeGetArray()), prunedBlock.toLevelDBContent());
        } catch (Exception e) {
            store.cancelBatch();
            throw e;
        } finally {
            store.endBatch();
        }
    }

    private boolean contained(List<? extends StoredTransaction> transactions, TID transactionId) {
        for (Transaction prunedTransaction : transactions) {
            if (prunedTransaction.getID().equals(transactionId)) {
                return true;
            }
        }
        return false;
    }


    @Override
    public PersistenceStatistics writeBlock(StoredBlock b) throws HyperLedgerException {
        Stopwatch stopwatch = Stopwatch.createStarted();
        PersistenceStatistics stats;
        try {
            store.startBatch();

            store.put(OrderedMapStoreKey.createKey(OrderedMapStoreKey.KeyType.BLOCKHEADER, b.getID().unsafeGetArray()),
                    b.toLevelDBHeader());
            store.put(OrderedMapStoreKey.createKey(OrderedMapStoreKey.KeyType.BLOCKCONTENT, b.getID().unsafeGetArray()),
                    b.toLevelDBContent());
            for (StoredTransaction t : b.getTransactions()) {
                byte[] stored = store.get(OrderedMapStoreKey.createKey(OrderedMapStoreKey.KeyType.TX, t.getID().unsafeGetArray()));
                if (stored != null) {
                    t.addBlock(b.getID());
                    store.put(OrderedMapStoreKey.createKey(OrderedMapStoreKey.KeyType.TX, t.getID().unsafeGetArray()), t.toLevelDB());
                } else
                    writeTx(t, b.getID());
            }
        } catch (Exception e) {
            store.cancelBatch();
            throw e;
        } finally {
            store.endBatch();
            stats = new PersistenceStatistics(nRead, readTime, stopwatch.stop().elapsed(TimeUnit.MILLISECONDS));
            nRead = 0;
            readTime = 0;
        }
        return stats;
    }

    public boolean writeMisc(BID id, byte[] data) {
        try {
            store.startBatch();
            store.put(OrderedMapStoreKey.createKey(OrderedMapStoreKey.KeyType.MISC, id.unsafeGetArray()), data);
        } catch (Exception e) {
            store.cancelBatch();
            throw e;
        } finally {
            store.endBatch();
        }
        return true;
    }

    public byte[] readMisc(BID id) {
        return store.get(OrderedMapStoreKey.createKey(OrderedMapStoreKey.KeyType.MISC, id.unsafeGetArray()));
    }

    @Override
    public void readHeaders(Map<BID, StoredHeader> headers) throws HyperLedgerException {
        store.forAll(OrderedMapStoreKey.KeyType.BLOCKHEADER, (key, data) -> {
            StoredHeader b = StoredHeader.fromLevelDB(data);
            headers.put(b.getID(), b);
            return true;
        });
    }

    @Override
    public boolean isEmpty() {
        return store.isEmpty();
    }


    @Override
    public boolean hasTransaction(TID hash) throws HyperLedgerException {
        return store.get(OrderedMapStoreKey.createKey(OrderedMapStoreKey.KeyType.TX, hash.unsafeGetArray())) != null;
    }


    @Override
    public List<TID> readBlockTIDList(BID hash) throws HyperLedgerException {
        byte[] data = store.get(OrderedMapStoreKey.createKey(OrderedMapStoreKey.KeyType.BLOCKCONTENT, hash.unsafeGetArray()));
        if (data != null)
            try {
                LevelDBStore.BLOCKCONTENT c = LevelDBStore.BLOCKCONTENT.parseFrom(data);
                List<TID> txs = new ArrayList<>(c.getTxHashesCount());
                for (ByteString txhash : c.getTxHashesList()) {
                    byte[] id = txhash.toByteArray();
                    if (id[32] == 0) {
                        txs.add(new TID(Arrays.copyOfRange(id, 0, 32)));
                    }
                }
                return txs;
            } catch (InvalidProtocolBufferException e) {
                throw new HyperLedgerException(e);
            }
        return new ArrayList<>();
    }

    @Override
    public boolean hasBlock(BID hash) throws HyperLedgerException {
        return readHeader(hash) != null;
    }

    protected void addSpendIndex(Outpoint outpoint, TID spendingTx) {
        byte[] k = OrderedMapStoreKey.createKey(OrderedMapStoreKey.KeyType.SPEND, toBytesArray(outpoint.hashCode()));
        byte[] data = store.get(k);
        if (data == null) {
            data = new byte[4];
        } else {
            data = Arrays.copyOf(data, data.length + 4);
        }

        byte[] id = Arrays.copyOf(spendingTx.unsafeGetArray(), 4);
        System.arraycopy(id, 0, data, data.length - id.length, id.length);
        store.put(k, data);
    }

    protected void addDataIndex(OrderedMapStoreKey.KeyType type, byte[] kid, final byte[] id) throws HyperLedgerException {
        byte[] data = new byte[0];

        byte[] k = OrderedMapStoreKey.createKey(type, kid);
        byte[] lastKey = Arrays.copyOf(k, k.length + 4);
        lastKey[lastKey.length - 1] = (byte) 0xff;
        lastKey[lastKey.length - 2] = (byte) 0xff;
        lastKey[lastKey.length - 3] = (byte) 0xff;
        lastKey[lastKey.length - 4] = (byte) 0xff;

        byte[] currentKey = store.getFloorKey(lastKey);
        if (currentKey != null) {
            boolean found = true;
            for (int i = 0; found && i < currentKey.length && i < k.length; ++i) {
                if (currentKey[i] != k[i]) {
                    found = false;
                }
            }
            if (found) {
                data = store.get(currentKey);
            } else {
                currentKey = Arrays.copyOf(k, k.length + 4);
            }
        } else {
            currentKey = Arrays.copyOf(k, k.length + 4);
        }

        if (data.length < 4000) {
            boolean different = false;
            if (data.length >= id.length)
                for (int i = 0; !different && i < id.length; ++i)
                    if (data[data.length - id.length + i] != id[i])
                        different = true;
            if (data.length == 0 || different) {
                byte[] nids = Arrays.copyOf(data, data.length + id.length);
                System.arraycopy(id, 0, nids, data.length, id.length);
                store.put(currentKey, nids);
            }
        } else {
            int n = 0;
            for (int i = currentKey.length - 4; i < currentKey.length; ++i) {
                n <<= 8;
                n += currentKey[i] & 0xff;
            }

            ++n;

            for (int i = currentKey.length - 1; i >= currentKey.length - 4; --i) {
                currentKey[i] = (byte) (n & 0xff);
                n >>>= 8;
            }
            store.put(currentKey, id);
        }
    }

    private byte[] toBytesArray(int hashcode) {
        WireFormat.ArrayWriter writer = new WireFormat.ArrayWriter();
        try {
            writer.writeUint32(hashcode);
        } catch (IOException e) {
        }
        return writer.toByteArray();
    }

    @Override
    public boolean probablyHadTransactionsWithOutput(Script script) throws HyperLedgerException {
        return store.get(OrderedMapStoreKey.createKey(
                OrderedMapStoreKey.KeyType.OUTSCRIPT, Arrays.copyOf(toBytesArray(script.hashCode()), 8))) != null;
    }

    @Override
    public Set<StoredTransaction> getTransactionsWithOutput(final Script script) throws HyperLedgerException {
        final Set<StoredTransaction> txs = new HashSet<>();
        store.forAll(OrderedMapStoreKey.KeyType.OUTSCRIPT, toBytesArray(script.hashCode()), (key, data) -> {
                    for (int i = 0; i < data.length; i += 4) {
                        byte id[] = Arrays.copyOfRange(data, i, i + 4);
                        store.forAll(OrderedMapStoreKey.KeyType.TX, id, (k, d) -> {
                            StoredTransaction t = StoredTransaction.fromLevelDB(d);
                            for (TransactionOutput o : t.getOutputs()) {
                                if (o.getScript().equals(script)) {
                                    txs.add(t);
                                    break;
                                }
                            }
                            return true;
                        });
                    }
                    return true;
                }
        );
        return txs;
    }

    @Override
    public Set<StoredTransaction> getSpendingTransactions(Outpoint outpoint) throws HyperLedgerException {
        final Set<StoredTransaction> txs = new HashSet<>();
        byte[] spend = store.get(OrderedMapStoreKey.createKey(OrderedMapStoreKey.KeyType.SPEND, toBytesArray(outpoint.hashCode())));
        if (spend != null) {
            for (int i = 0; i < spend.length; i += 4) {
                byte[] tk = Arrays.copyOfRange(spend, i, i + 4);
                store.forAll(OrderedMapStoreKey.KeyType.TX, tk, (tx, txdata) -> {
                    boolean found = false;
                    StoredTransaction potentialSpender = StoredTransaction.fromLevelDB(txdata);
                    for (TransactionInput in : potentialSpender.getInputs()) {
                        if (in.getSource().equals(outpoint)) {
                            found = true;
                            txs.add(potentialSpender);
                            break;
                        }
                    }
                    return !found;
                });
            }
        }
        return txs;
    }
}
