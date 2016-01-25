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
import org.hyperledger.core.bitcoin.BitcoinPersistentBlocks;
import org.hyperledger.core.bitcoin.GenesisBlocks;
import org.hyperledger.core.color.ColoredValidatorConfig;
import org.hyperledger.core.color.ColoredValidatorFactory;
import org.hyperledger.core.kvstore.MemoryStore;
import org.hyperledger.core.signed.BlockSignatureConfig;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.*;

public class BlockStoreTest {

    @Test
    public void blockStoreLoadTest() throws HyperLedgerException {
        ValidatorChain validatorChain = new ValidatorChain(
                new UnitTestBitcoinValidatorFactory(),
                new ColoredValidatorFactory(new ColoredValidatorConfig(true, false))
        );

        DefaultBlockStore blockStore = new DefaultBlockStore(
                validatorChain,
                new BitcoinPersistentBlocks(new MemoryStore()), new CoreOutbox(), new ClientEventQueue(), PrunerSettings.NO_PRUNING, BlockSignatureConfig.DISABLED);

        Block genesis = GenesisBlocks.regtest;

        blockStore.addGenesis(genesis);

        Transaction t1 = Transaction.create().inputs(TransactionInput.create().build())
                .outputs(TransactionOutput.create().value(1).build()).build();

        Transaction t2 = Transaction.create().inputs(TransactionInput.create().build())
                .outputs(TransactionOutput.create().value(2).build()).build();

        Transaction t3 = Transaction.create().inputs(TransactionInput.create().build())
                .outputs(TransactionOutput.create().value(3).build()).build();

        Block b1 = Block.create().header(BitcoinHeader.create().previousID(genesis.getID()).build()).transactions(t1).build();
        blockStore.addHeader(b1.getHeader());
        List<BID> missing = blockStore.getMissingBlocks(10);
        assertEquals(1, missing.size());
        assertEquals(b1.getID(), blockStore.getMissingBlocks(10).get(0));

        blockStore.addBlock(b1);
        assertTrue(blockStore.getMissingBlocks(10).isEmpty());

        Block b2 = Block.create().header(BitcoinHeader.create().previousID(b1.getID()).build()).transactions(t2).build();
        blockStore.addHeader(b2.getHeader());
        assertEquals(b2.getID(), blockStore.getMissingBlocks(10).get(0));

        Block b3 = Block.create().header(BitcoinHeader.create().previousID(b2.getID()).build()).transactions(t3).build();
        blockStore.addHeader(b3.getHeader());
        assertEquals(b2.getID(), blockStore.getMissingBlocks(10).get(0));
        assertEquals(b3.getID(), blockStore.getMissingBlocks(10).get(1));

        blockStore.addBlock(b2);
        assertEquals(b3.getID(), blockStore.getMissingBlocks(10).get(0));


        blockStore.addBlock(b3);
        assertTrue(blockStore.getMissingBlocks(10).isEmpty());
    }

    @Test
    public void reorgtest() throws HyperLedgerException, ExecutionException, InterruptedException {

        ValidatorChain validatorChain = new ValidatorChain(
                new UnitTestBitcoinValidatorFactory(),
                new ColoredValidatorFactory(new ColoredValidatorConfig(true, false))
        );

        DefaultBlockStore blockStore = new DefaultBlockStore(
                validatorChain,
                new BitcoinPersistentBlocks(new MemoryStore()), new CoreOutbox(), new ClientEventQueue(), PrunerSettings.NO_PRUNING, BlockSignatureConfig.DISABLED);

        Block genesis = GenesisBlocks.regtest;

        blockStore.addGenesis(genesis);
        assertNotNull(blockStore.getBlock(genesis.getID()));
        assertEquals(0, blockStore.getFullHeight());
        assertEquals(0, blockStore.getSpvHeight());

        HeaderStoredInfo ri;

        // g - b2
        BitcoinHeader b2 = BitcoinHeader.create()
                .previousID(genesis.getID()).build();

        ri = blockStore.addHeader(b2);
        assertEquals(1, ri.getSpvHeight());
        assertArrayEquals(new BID[]{b2.getID()}, ri.getAddedToTrunk().toArray());
        assertArrayEquals(new BID[]{}, ri.getRemovedFromFrunk().toArray());
        assertEquals(blockStore.getFullTop(), genesis.getID());
        assertEquals(blockStore.getSpvTop(), b2.getID());


        // g - b2 - b3
        BitcoinHeader b3 = BitcoinHeader.create()
                .previousID(b2.getID()).build();

        ri = blockStore.addHeader(b3);
        assertEquals(2, ri.getSpvHeight());
        assertArrayEquals(new BID[]{b3.getID()}, ri.getAddedToTrunk().toArray());
        assertArrayEquals(new BID[]{}, ri.getRemovedFromFrunk().toArray());
        assertEquals(blockStore.getFullTop(), genesis.getID());
        assertEquals(blockStore.getSpvTop(), b3.getID());

        // g - b2 - b3
        //     + -- b4
        BitcoinHeader b4 = BitcoinHeader.create()
                .createTime(1) // make it different to b3
                .previousID(b2.getID()).build();

        ri = blockStore.addHeader(b4);
        assertEquals(2, ri.getSpvHeight());
        assertArrayEquals(new BID[]{}, ri.getAddedToTrunk().toArray());
        assertArrayEquals(new BID[]{}, ri.getRemovedFromFrunk().toArray());
        assertEquals(blockStore.getFullTop(), genesis.getID());
        assertEquals(blockStore.getSpvTop(), b3.getID());

        // g - b2 - b3
        //     + -- b4 - b5
        BitcoinHeader b5 = BitcoinHeader.create()
                .previousID(b4.getID()).build();

        ri = blockStore.addHeader(b5);
        assertEquals(3, ri.getSpvHeight());
        assertArrayEquals(new BID[]{b4.getID(), b5.getID()}, ri.getAddedToTrunk().toArray());
        assertArrayEquals(new BID[]{b3.getID()}, ri.getRemovedFromFrunk().toArray());
        assertEquals(blockStore.getFullTop(), genesis.getID());
        assertEquals(blockStore.getSpvTop(), b5.getID());

        // g - b2 - b3 - b6
        //     + -- b4 - b5
        BitcoinHeader b6 = BitcoinHeader.create()
                .previousID(b3.getID()).build();

        ri = blockStore.addHeader(b6);
        assertEquals(3, ri.getSpvHeight());
        assertArrayEquals(new BID[]{}, ri.getAddedToTrunk().toArray());
        assertArrayEquals(new BID[]{}, ri.getRemovedFromFrunk().toArray());
        assertEquals(blockStore.getFullTop(), genesis.getID());
        assertEquals(blockStore.getSpvTop(), b5.getID());

        // g - b2 - b3 - b6 - b7
        //     + -- b4 - b5
        BitcoinHeader b7 = BitcoinHeader.create()
                .previousID(b6.getID()).build();
        ri = blockStore.addHeader(b7);
        assertEquals(4, ri.getSpvHeight());
        assertArrayEquals(new BID[]{b3.getID(), b6.getID(), b7.getID()}, ri.getAddedToTrunk().toArray());
        assertArrayEquals(new BID[]{b4.getID(), b5.getID()}, ri.getRemovedFromFrunk().toArray());
        assertEquals(blockStore.getFullTop(), genesis.getID());
        assertEquals(blockStore.getSpvTop(), b7.getID());
    }
}
