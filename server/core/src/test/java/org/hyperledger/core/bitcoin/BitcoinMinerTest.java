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

import org.hyperledger.account.KeyListChain;
import org.hyperledger.common.*;
import org.hyperledger.core.*;
import org.hyperledger.core.color.ColoredValidatorConfig;
import org.hyperledger.core.color.ColoredValidatorFactory;
import org.hyperledger.core.kvstore.MemoryStore;
import org.hyperledger.core.signed.BlockSignatureConfig;
import org.hyperledger.core.signed.BlockSignatureValidatorFactory;
import org.hyperledger.core.signed.SignedRegtestValidatorConfig;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class BitcoinMinerTest {

    private PrivateKey minerPrivateKey;
    private Address minerAddress;
    private List<String> publicKeyStrings;

    @Before
    public void init() throws HyperLedgerException {
        minerPrivateKey = PrivateKey.createNew(true);
        KeyListChain minerKeyChain = new KeyListChain(minerPrivateKey);
        minerAddress = minerKeyChain.getNextReceiverAddress();

        publicKeyStrings = new ArrayList<>();
        publicKeyStrings.add(ByteUtils.toHex(minerPrivateKey.getPublic().toByteArray()));
        publicKeyStrings.add(ByteUtils.toHex(PrivateKey.createNew(true).getPublic().toByteArray()));
        publicKeyStrings.add(ByteUtils.toHex(PrivateKey.createNew(true).getPublic().toByteArray()));
    }

    @Test
    public void mineRegularChain() throws HyperLedgerException {
        ValidatorChain validatorChain = new ValidatorChain(
                new UnitTestBitcoinValidatorFactory(),
                new ColoredValidatorFactory(new ColoredValidatorConfig(true, false))
        );
        DefaultBlockStore blockStore = new DefaultBlockStore(validatorChain,
                new BitcoinPersistentBlocks(new MemoryStore()), new CoreOutbox(), new ClientEventQueue(), PrunerSettings.NO_PRUNING, BlockSignatureConfig.DISABLED);

        Block genesisBlock = GenesisBlocks.regtest;
        blockStore.addGenesis(genesisBlock);
        BitcoinRegtestValidatorConfig validatorConfig = new BitcoinRegtestValidatorConfig();

        BitcoinMiner miner = new BitcoinMiner(blockStore, new MiningConfig(true, minerAddress.toString(), 0), validatorConfig, BlockSignatureConfig.DISABLED);
        Block block1 = miner.mineAndStoreOneBlock();
        assertEquals(BitcoinHeader.class, block1.getHeader().getClass());

        Block notStoredBlock = miner.mineOneBlock();
        assertEquals(BitcoinHeader.class, notStoredBlock.getHeader().getClass());

        Block block2 = miner.mineAndStoreOneBlock();
        assertEquals(BitcoinHeader.class, block2.getHeader().getClass());

        assertEquals(genesisBlock.getID(), block1.getPreviousID());
        assertEquals(block1.getID(), notStoredBlock.getPreviousID());
        assertEquals(block1.getID(), block2.getPreviousID());

        // do not mine on top of a block not in the block store
        assertNotEquals(block2.getID(), notStoredBlock.getPreviousID());
    }

    @Test
    public void mineSignedChain() throws HyperLedgerException {
        BlockSignatureConfig blockSignatureConfig = new BlockSignatureConfig(true, PrivateKey.serializeWIF(minerPrivateKey), 1, publicKeyStrings);

        ValidatorChain validatorChain = new ValidatorChain(
                new UnitTestBitcoinValidatorFactory(),
                new ColoredValidatorFactory(new ColoredValidatorConfig(true, false)),
                new BlockSignatureValidatorFactory(blockSignatureConfig)
        );
        DefaultBlockStore blockStore = new DefaultBlockStore(validatorChain,
                new BitcoinPersistentBlocks(new MemoryStore()), new CoreOutbox(), new ClientEventQueue(), PrunerSettings.NO_PRUNING, BlockSignatureConfig.DISABLED);

        byte[] inScriptBytes = Script.create().blockSignature(blockSignatureConfig.getRequiredSignatureCount(), blockSignatureConfig.getPublicKeys()).build().toByteArray();
        Block genesisBlock = GenesisBlocks.unittestWithHeaderSignature(inScriptBytes);
        blockStore.addGenesis(genesisBlock);
        SignedRegtestValidatorConfig validatorConfig = new SignedRegtestValidatorConfig();

        BitcoinMiner miner = new BitcoinMiner(blockStore, new MiningConfig(true, minerAddress.toString(), 0), validatorConfig, blockSignatureConfig);
        Block block1 = miner.mineAndStoreOneBlock();
        assertEquals(HeaderWithSignatures.class, block1.getHeader().getClass());

        Block notStoredBlock = miner.mineOneBlock();
        assertEquals(HeaderWithSignatures.class, notStoredBlock.getHeader().getClass());

        Block block2 = miner.mineAndStoreOneBlock();
        assertEquals(HeaderWithSignatures.class, block2.getHeader().getClass());

        assertEquals(genesisBlock.getID(), block1.getPreviousID());
        assertEquals(block1.getID(), notStoredBlock.getPreviousID());
        assertEquals(block1.getID(), block2.getPreviousID());

        // do not mine on top of a block not in the block store
        assertNotEquals(block2.getID(), notStoredBlock.getPreviousID());
    }
}
