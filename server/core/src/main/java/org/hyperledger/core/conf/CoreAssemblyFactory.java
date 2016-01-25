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

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import org.hyperledger.HyperLedgerSettings;
import org.hyperledger.common.HyperLedgerException;
import org.hyperledger.common.LoggedHyperLedgerException;
import org.hyperledger.core.*;
import org.hyperledger.core.bitcoin.*;
import org.hyperledger.core.color.ColoredValidatorConfig;
import org.hyperledger.core.color.ColoredValidatorFactory;
import org.hyperledger.core.color.NativeAssetValidator;
import org.hyperledger.core.kvstore.LevelDBStore;
import org.hyperledger.core.kvstore.MemoryStore;
import org.hyperledger.core.kvstore.OrderedMapStore;
import org.hyperledger.core.signed.BlockSignatureConfig;
import org.hyperledger.core.signed.BlockSignatureValidatorFactory;
import org.hyperledger.core.signed.SignedRegtestValidatorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class CoreAssemblyFactory {
    private static final Logger log = LoggerFactory.getLogger(CoreAssemblyFactory.class);

    private static CoreAssembly assembly;
    private static HyperLedgerSettings settings;


    public static synchronized void initialize() {
        if (settings != null)
            throw new IllegalStateException("CoreAssemblyFactory is already initialized");

        settings = HyperLedgerSettings.getInstance();
        assembly = fromConfig(settings.getConfiguration());
    }

    public static synchronized void initialize(Config conf) {
        HyperLedgerSettings.initialize(conf);
        initialize();
    }

    public static CoreAssembly getAssembly() {
        if (assembly == null)
            throw new IllegalStateException("CoreAssemblyFactory is not initialized");

        return assembly;
    }

    public static CoreAssembly fromConfig(Config config) {
        BlockSignatureSettingsFactory bs = new BlockSignatureSettingsFactory(config);
        ValidatorConfigFactory vf = new ValidatorConfigFactory(config, bs.get());
        PersistentBlocksFactory p = new PersistentBlocksFactory(config);
        PrunerSettingsFactory pr = new PrunerSettingsFactory(config);
        MiningSettingsFactory ms = new MiningSettingsFactory(config);

        return new CoreAssembly(
                vf.get(),
                p.get(),
                new CoreOutbox(),
                new ClientEventQueue(),
                pr.get(),
                bs.get(),
                ms.get()
        );
    }

    public static void reset() {
        assembly.stop();
        assembly = null;
        settings = null;
    }

    static class LevelDBStoreFactory implements Supplier<LevelDBStore> {
        final String db;
        final int cacheSize;

        public LevelDBStoreFactory(Config config) {
            this.db = config.getString("database");
            this.cacheSize = config.getInt("cacheSize");
        }

        @Override
        public LevelDBStore get() {
            return new LevelDBStore(db, cacheSize);
        }
    }

    static class MemstoreFactory implements Supplier<MemoryStore> {
        @Override
        public MemoryStore get() {
            return new MemoryStore();
        }
    }

    static class PersistentBlocksFactory implements Supplier<PersistentBlocks> {
        final Supplier<? extends OrderedMapStore> storeFactory;

        public PersistentBlocksFactory(Config config) {
            if (config.hasPath("store.leveldb") && config.hasPath("store.memory")) {
                throw new ConfigException.BadValue(config.origin(), "store", "only one store is allowed: either memory or leveldb");
            } else if (config.hasPath("store.leveldb")) {
                Config leveldbConfig = config.getConfig("store.leveldb");
                if (config.hasPath("store.default-leveldb"))
                    leveldbConfig = leveldbConfig.withFallback(config.getConfig("store.default-leveldb"));

                storeFactory = new LevelDBStoreFactory(leveldbConfig);
            } else if (config.hasPath("store.memory")) {
                storeFactory = new MemstoreFactory();
            } else {
                throw new ConfigException.BadValue(config.origin(), "store", "store must be either memory or leveldb");
            }
        }

        @Override
        public PersistentBlocks get() {
            return new BitcoinPersistentBlocks(storeFactory.get());
        }
    }

    static class PrunerSettingsFactory implements Supplier<PrunerSettings> {
        private final PrunerSettings settings;

        public PrunerSettingsFactory(Config config) {
            Config pr = config.getConfig("store.pruning");
            this.settings = new PrunerSettings(
                    pr.getBoolean("enabled"),
                    pr.getInt("pruneAfterEvery"),
                    pr.getInt("pruneOnlyLowerThanHeight"),
                    pr.getInt("doNotPruneTopBlocks"),
                    pr.getInt("pruneFrom"));
        }

        @Override
        public PrunerSettings get() {
            return settings;
        }
    }

    static class ValidatorConfigFactory implements Supplier<ValidatorChain> {
        ValidatorChain validatorChain;

        public ValidatorConfigFactory(Config config) {
            this(config, BlockSignatureConfig.DISABLED);
        }

        public ValidatorConfigFactory(Config config, BlockSignatureConfig blockSignatureConfig) {
            List<ValidatorFactory> validatorFactories = new ArrayList<>();
            validatorFactories.add(createBitcoinValidatorFactory(config));
            validatorFactories.add(createColoredValidatorFactory(config));
            if (blockSignatureConfig.enabled()) {
                validatorFactories.add(createBlockSignatureValidatorFactory(blockSignatureConfig));
            }
            validatorChain = new ValidatorChain(validatorFactories);
        }

        private BitcoinValidatorFactory createBitcoinValidatorFactory(Config config) {
            String chain = config.getString("blockchain.chain");
            if ("testnet3".equals(chain)) {
                return new BitcoinValidatorFactory(new BitcoinTestnetValidatorConfig());
            } else if ("regtest".equals(chain)) {
                BitcoinRegtestValidatorConfig bitcoinConfig = new BitcoinRegtestValidatorConfig();
                if (config.hasPath("feature") && config.getBoolean("feature.native-assets"))
                    return new BitcoinValidatorFactory(bitcoinConfig, new NativeAssetValidator(bitcoinConfig));
                else
                    return new BitcoinValidatorFactory(bitcoinConfig);
            } else if ("production".equals(chain)) {
                return new BitcoinValidatorFactory(new BitcoinProductionValidatorConfig());
            } else if ("signedregtest".equals(chain)) {
                return new BitcoinValidatorFactory(new SignedRegtestValidatorConfig());
            } else {
                throw new ConfigException.BadValue(config.origin(), "blockchain.chain", "Invalid chain: " + chain);
            }
        }

        private ColoredValidatorFactory createColoredValidatorFactory(Config config) {
            String chain = config.getString("blockchain.chain");
            if ("testnet3".equals(chain)) {
                return new ColoredValidatorFactory(new ColoredValidatorConfig(true, true));
            } else if ("regtest".equals(chain)) {
                return new ColoredValidatorFactory(new ColoredValidatorConfig(true, true));
            } else if ("production".equals(chain)) {
                return new ColoredValidatorFactory(new ColoredValidatorConfig(true, false));
            } else if ("signedregtest".equals(chain)) {
                return new ColoredValidatorFactory(new ColoredValidatorConfig(true, true));
            } else {
                throw new ConfigException.BadValue(config.origin(), "blockchain.chain", "Invalid chain: " + chain);
            }
        }

        private BlockSignatureValidatorFactory createBlockSignatureValidatorFactory(BlockSignatureConfig blockSignatureConfig) {
            return new BlockSignatureValidatorFactory(blockSignatureConfig);
        }

        @Override
        public ValidatorChain get() {
            return validatorChain;
        }
    }

    static class BlockSignatureSettingsFactory implements Supplier<BlockSignatureConfig> {
        private final BlockSignatureConfig blockSignatureConfig;

        public BlockSignatureSettingsFactory(Config config) {
            Config c = config.getConfig("blockSignature");
            BlockSignatureConfig tempBlockSignatureConfig = BlockSignatureConfig.DISABLED;
            if (c != null) {
                boolean enabled = c.getBoolean("enabled");
                if (enabled) {
                    String minerPrivateKey = c.getString("minerPrivateKey");
                    int requiredSignatureCount = c.getInt("requiredSignatureCount");
                    List<String> publicKeyStrings = c.getStringList("publicKeys");
                    if (minerPrivateKey != null && requiredSignatureCount > 0 && !publicKeyStrings.isEmpty()) {
                        try {
                            tempBlockSignatureConfig = new BlockSignatureConfig(true, minerPrivateKey, requiredSignatureCount, publicKeyStrings);
                        } catch (HyperLedgerException e) {
                            LoggedHyperLedgerException.loggedError(log, "Invalid blockSignature config", e);
                        }
                    }
                }
            }
            blockSignatureConfig = tempBlockSignatureConfig;
        }

        @Override
        public BlockSignatureConfig get() {
            return blockSignatureConfig;
        }
    }


    static class MiningSettingsFactory implements Supplier<MiningConfig> {
        private final MiningConfig miningConfig;

        public MiningSettingsFactory(Config config) {
            String minerAddress = null;
            try {
                Config c = config.getConfig("mining");
                if (c != null) {
                    boolean enabled = c.getBoolean("enabled");
                    if (!c.hasPath("minerAddress")) {
                        throw new ConfigException.Missing("mining.minerAddress");
                    }
                    minerAddress = c.getString("minerAddress");
                    if (enabled) {
                        int delayBetweenMiningBlocksSecs = c.getInt("delayBetweenMiningBlocksSecs");
                        miningConfig = new MiningConfig(true, minerAddress, delayBetweenMiningBlocksSecs);
                    } else {
                        miningConfig = new MiningConfig(false, minerAddress, 0);
                    }
                } else {
                    miningConfig = MiningConfig.DISABLED;
                }
            } catch (HyperLedgerException e) {
                throw new ConfigException.BadValue(config.origin(), "mining.minerAddress", "Cannot decode the minerAddress: " + minerAddress);
            }
        }

        @Override
        public MiningConfig get() {
            return miningConfig;
        }
    }

}
