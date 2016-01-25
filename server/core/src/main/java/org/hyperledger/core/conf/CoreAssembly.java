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
package org.hyperledger.core.conf;

import org.hyperledger.common.HyperLedgerException;
import org.hyperledger.core.*;
import org.hyperledger.core.bitcoin.BitcoinBlockStore;
import org.hyperledger.core.bitcoin.BitcoinMiner;
import org.hyperledger.core.bitcoin.BitcoinValidatorFactory;
import org.hyperledger.core.bitcoin.MiningConfig;
import org.hyperledger.core.signed.BlockSignatureConfig;

/**
 *
 */
public class CoreAssembly {
    private final ValidatorChain validatorChain;
    private final PersistentBlocks persistentBlocks;
    private final CoreOutbox coreOutbox;
    private final ClientEventQueue clientEventQueue;
    private final PrunerSettings prunerSettings;
    private BlockSignatureConfig blockSignatureConfig;
    private MiningConfig miningConfig;
    private final BitcoinBlockStore blockStore;
    private BitcoinMiner miner;

    public CoreAssembly(ValidatorChain validatorChain,
                        PersistentBlocks persistentBlocks,
                        CoreOutbox coreOutbox,
                        ClientEventQueue clientEventQueue,
                        PrunerSettings prunerSettings,
                        BlockSignatureConfig blockSignatureConfig,
                        MiningConfig miningConfig) {

        this.validatorChain = validatorChain;
        this.persistentBlocks = persistentBlocks;
        this.coreOutbox = coreOutbox;
        this.clientEventQueue = clientEventQueue;
        this.prunerSettings = prunerSettings;
        this.blockSignatureConfig = blockSignatureConfig;
        this.miningConfig = miningConfig;
        this.blockStore = new DefaultBlockStore(validatorChain,
                persistentBlocks,
                coreOutbox,
                clientEventQueue,
                prunerSettings,
                blockSignatureConfig);
    }

    public void start() throws HyperLedgerException {
        blockStore.start();
        miner = new BitcoinMiner(blockStore, miningConfig, getValidatorChain().getValidatorFactory(BitcoinValidatorFactory.class).getConfig(), getBlockSignatureConfig());
        if (miningConfig.enabled()) {
            miner.start();
        }
    }

    public void stop() {
        if (miningConfig.enabled()) {
            miner.stop();
        }
        blockStore.stop();
    }

    public BitcoinBlockStore getBlockStore() {
        return blockStore;
    }

    public ValidatorChain getValidatorChain() {
        return validatorChain;
    }

    public PersistentBlocks getPersistentBlocks() {
        return persistentBlocks;
    }

    public CoreOutbox getCoreOutbox() {
        return coreOutbox;
    }

    public ClientEventQueue getClientEventQueue() {
        return clientEventQueue;
    }

    public PrunerSettings getPrunerSettings() {
        return prunerSettings;
    }

    public BlockSignatureConfig getBlockSignatureConfig() {
        return blockSignatureConfig;
    }

    public MiningConfig getMiningConfig() {
        return miningConfig;
    }

    public BitcoinMiner getMiner() {
        return miner;
    }
}
