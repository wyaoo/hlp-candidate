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

import org.hyperledger.common.*;
import org.hyperledger.core.PersistentBlocks;
import org.hyperledger.core.StoredBlock;
import org.hyperledger.core.StoredHeader;
import org.hyperledger.core.StoredTransaction;
import org.hyperledger.core.kvstore.LevelDBStore;
import org.hyperledger.core.kvstore.MemoryStore;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertTrue;

public class PersistentBlocksTest {

    private StoredBlock readBlock(String blockfile) throws IOException, HyperLedgerException {
        Reader reader = new InputStreamReader(this.getClass().getResource("/" + blockfile).openStream());
        char[] buff = new char[1024];
        StringBuilder result = new StringBuilder();
        int len;
        while ((len = reader.read(buff)) > 0)
            result.append(buff, 0, len);

        Block b = Block.fromWireDump(result.toString(), WireFormatter.bitcoin, BitcoinHeader.class);
        List<StoredTransaction> storedTxList = new ArrayList<>();
        for (Transaction t : b.getTransactions()) {
            storedTxList.add(new StoredTransaction(t, 0));
        }

        return new StoredBlock(new StoredHeader(b.getHeader(), 0.0, 0), storedTxList);
    }

    @Test
    public void memtest() throws IOException, HyperLedgerException {
        MemoryStore memstore = new MemoryStore();
        PersistentBlocks blocks = new BitcoinPersistentBlocks(memstore);
        StoredBlock b1 = readBlock("000000000000000001f942eb4bfa0aeccb6a14c268f4c72d5fff17270da771b9.hexblock");
        blocks.writeBlock(b1);
        StoredBlock b2 = readBlock("000000000000000001f942eb4bfa0aeccb6a14c268f4c72d5fff17270da771b9.hexblock");
        blocks.writeBlock(b2);

        assertTrue(blocks.hasBlock(new BID("000000000000000001f942eb4bfa0aeccb6a14c268f4c72d5fff17270da771b9")));
        assertTrue(blocks.hasTransaction(new TID("df39000a50d3d115cee18ab7ad1a65f0b1f012e5c58cc248ba325392b174201b")));

        blocks.readBlock(new BID("000000000000000001f942eb4bfa0aeccb6a14c268f4c72d5fff17270da771b9"));

        int spentOutputs = 0;
        for (Transaction t : b1.getTransactions()) {
            for (Coin o : t.getCoins()) {
                Set<StoredTransaction> spendingTransaction = blocks.getSpendingTransactions(o.getOutpoint());
                if (!spendingTransaction.isEmpty()) {
                    ++spentOutputs;
                }
            }
        }
        assertTrue(spentOutputs == 1158);

        int nUses = 0;
        for (Transaction t : b2.getTransactions()) {
            for (TransactionOutput o : t.getOutputs()) {
                Address a = o.getOutputAddress();
                if (a != null)
                    nUses += blocks.getTransactionsWithOutput(a.getAddressScript()).size();
            }
        }
        assert (nUses == 28533);
    }


    @Test
    public void diskTest1() throws IOException, HyperLedgerException {
        LevelDBStore diskstore = new LevelDBStore("/tmp/" + UUID.randomUUID(), 100);
        diskstore.open();
        diskstore.startBatch();
        PersistentBlocks blocks = new BitcoinPersistentBlocks(diskstore);
        StoredBlock b1 = readBlock("000000000000000001f942eb4bfa0aeccb6a14c268f4c72d5fff17270da771b9.hexblock");
        blocks.writeBlock(b1);
        StoredBlock b2 = readBlock("000000000000000001f942eb4bfa0aeccb6a14c268f4c72d5fff17270da771b9.hexblock");
        blocks.writeBlock(b2);
        diskstore.endBatch();

        assertTrue(blocks.hasBlock(new BID("000000000000000001f942eb4bfa0aeccb6a14c268f4c72d5fff17270da771b9")));
        assertTrue(blocks.hasTransaction(new TID("df39000a50d3d115cee18ab7ad1a65f0b1f012e5c58cc248ba325392b174201b")));

        blocks.readBlock(new BID("000000000000000001f942eb4bfa0aeccb6a14c268f4c72d5fff17270da771b9"));

        int spentOutputs = 0;
        for (Transaction t : b1.getTransactions()) {
            for (Coin o : t.getCoins()) {
                Set<StoredTransaction> spendingTransaction = blocks.getSpendingTransactions(o.getOutpoint());
                if (!spendingTransaction.isEmpty()) {
                    ++spentOutputs;
                }
            }
        }
        assertTrue(spentOutputs == 1158);

        int nUses = 0;
        for (Transaction t : b2.getTransactions()) {
            for (TransactionOutput o : t.getOutputs()) {
                Address a = o.getOutputAddress();
                if (a != null)
                    nUses += blocks.getTransactionsWithOutput(a.getAddressScript()).size();
            }
        }
        assert (nUses == 28533);
        diskstore.close();
    }

    @Test
    public void diskTest2() throws IOException, HyperLedgerException {
        LevelDBStore diskstore = new LevelDBStore("/tmp/" + UUID.randomUUID(), 100);
        diskstore.open();
        diskstore.startBatch();
        PersistentBlocks blocks = new BitcoinPersistentBlocks(diskstore);
        StoredBlock b1 = readBlock("000000000000000001f942eb4bfa0aeccb6a14c268f4c72d5fff17270da771b9.hexblock");
        blocks.writeBlock(b1);
        StoredBlock b2 = readBlock("000000000000000001f942eb4bfa0aeccb6a14c268f4c72d5fff17270da771b9.hexblock");
        blocks.writeBlock(b2);

        assertTrue(blocks.hasBlock(new BID("000000000000000001f942eb4bfa0aeccb6a14c268f4c72d5fff17270da771b9")));
        assertTrue(blocks.hasTransaction(new TID("df39000a50d3d115cee18ab7ad1a65f0b1f012e5c58cc248ba325392b174201b")));

        blocks.readBlock(new BID("000000000000000001f942eb4bfa0aeccb6a14c268f4c72d5fff17270da771b9"));

        int spentOutputs = 0;
        for (Transaction t : b1.getTransactions()) {
            for (Coin o : t.getCoins()) {
                Set<StoredTransaction> spendingTransaction = blocks.getSpendingTransactions(o.getOutpoint());
                if (!spendingTransaction.isEmpty()) {
                    ++spentOutputs;
                }
            }
        }
        assertTrue(spentOutputs == 1158);

        int nUses = 0;
        for (Transaction t : b2.getTransactions()) {
            for (TransactionOutput o : t.getOutputs()) {
                Address a = o.getOutputAddress();
                if (a != null)
                    nUses += blocks.getTransactionsWithOutput(a.getAddressScript()).size();
            }
        }
        assert (nUses == 28533);
        diskstore.endBatch();
        diskstore.close();
    }

    @Test
    public void diskTest3() throws IOException, HyperLedgerException {
        LevelDBStore diskstore = new LevelDBStore("/tmp/" + UUID.randomUUID(), 100);
        diskstore.open();
        PersistentBlocks blocks = new BitcoinPersistentBlocks(diskstore);
        StoredBlock b1 = readBlock("000000000000000001f942eb4bfa0aeccb6a14c268f4c72d5fff17270da771b9.hexblock");
        blocks.writeBlock(b1);
        StoredBlock b2 = readBlock("000000000000000001f942eb4bfa0aeccb6a14c268f4c72d5fff17270da771b9.hexblock");
        blocks.writeBlock(b2);

        assertTrue(blocks.hasBlock(new BID("000000000000000001f942eb4bfa0aeccb6a14c268f4c72d5fff17270da771b9")));
        assertTrue(blocks.hasTransaction(new TID("df39000a50d3d115cee18ab7ad1a65f0b1f012e5c58cc248ba325392b174201b")));

        blocks.readBlock(new BID("000000000000000001f942eb4bfa0aeccb6a14c268f4c72d5fff17270da771b9"));

        int spentOutputs = 0;
        for (Transaction t : b1.getTransactions()) {
            for (Coin o : t.getCoins()) {
                Set<StoredTransaction> spendingTransaction = blocks.getSpendingTransactions(o.getOutpoint());
                if (!spendingTransaction.isEmpty()) {
                    ++spentOutputs;
                }
            }
        }
        assertTrue(spentOutputs == 1158);

        int nUses = 0;
        for (Transaction t : b2.getTransactions()) {
            for (TransactionOutput o : t.getOutputs()) {
                Address a = o.getOutputAddress();
                if (a != null)
                    nUses += blocks.getTransactionsWithOutput(a.getAddressScript()).size();
            }
        }
        assert (nUses == 28533);
        diskstore.close();
    }
}
