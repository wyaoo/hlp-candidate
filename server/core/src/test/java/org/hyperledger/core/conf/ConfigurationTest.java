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

import com.typesafe.config.*;
import com.typesafe.config.ConfigException.BadValue;
import com.typesafe.config.ConfigException.Missing;
import com.typesafe.config.ConfigException.WrongType;
import org.hyperledger.core.PersistentBlocks;
import org.hyperledger.core.ValidatorChain;
import org.hyperledger.core.ValidatorConfig;
import org.hyperledger.core.bitcoin.BitcoinProductionValidatorConfig;
import org.hyperledger.core.color.ColoredValidatorConfig;
import org.hyperledger.core.conf.CoreAssemblyFactory.LevelDBStoreFactory;
import org.hyperledger.core.conf.CoreAssemblyFactory.PersistentBlocksFactory;
import org.hyperledger.core.conf.CoreAssemblyFactory.PrunerSettingsFactory;
import org.hyperledger.core.kvstore.LevelDBStore;
import org.junit.Test;

import java.util.Collections;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.function.Function.identity;
import static java.util.function.Predicate.isEqual;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.junit.Assert.*;

public class ConfigurationTest {

    static Config asConf(String c) {
        return ConfigFactory.parseString(c);
    }

    static Config asConfWithReference(String c) {
        return ConfigFactory.load(asConf(c)).getConfig("hyperledger");
    }

    static <C extends ConfigException> void expectConfigError(Class<C> errorType, Runnable r) {
        try {
            r.run();
            fail("Configuration exception " + errorType + " expected");
        } catch (ConfigException err) {
            assertThat(err, instanceOf(errorType));
        }
    }

    static Stream<ConfigObject> missingKeys(Function<String, Object> f, String... keys) {
        return Stream.of(keys)
                .map((ks) -> isEqual(ks).negate())
                .map((pred) -> Stream.of(keys).filter(pred))
                .map((ks) -> ks.collect(Collectors.toMap(identity(), f)))
                .map(ConfigValueFactory::fromMap);
    }

    //TODO: enable after fixing
    //@Test
    public void testInvalidBlockchainConfig() throws Exception {
        expectConfigError(Missing.class, () -> new CoreAssemblyFactory.ValidatorConfigFactory(asConf("")));
        expectConfigError(WrongType.class, () -> new CoreAssemblyFactory.ValidatorConfigFactory(asConf("blockchain: 1")));
        expectConfigError(Missing.class, () -> new CoreAssemblyFactory.ValidatorConfigFactory(asConf("blockchain: {}")));
        expectConfigError(BadValue.class, () -> new CoreAssemblyFactory.ValidatorConfigFactory(asConf("blockchain: {chain: slartibartfast}")));
    }

    private void assertConfig(String configString, boolean enforceColorRulesInMempool, boolean enforceColorRulesInBlocks) {
        ValidatorChain validatorChain = new CoreAssemblyFactory.ValidatorConfigFactory(asConf(configString)).get();
        assertThat(
                validatorChain.getValidatorFactory(0).getConfig(),
                instanceOf(BitcoinProductionValidatorConfig.class));
        ValidatorConfig config = validatorChain.getValidatorFactory(1).getConfig();
        assertThat(
                config,
                instanceOf(ColoredValidatorConfig.class));
        assertEquals(enforceColorRulesInMempool, ((ColoredValidatorConfig) config).isEnforceColorRulesInMempool());
        assertEquals(enforceColorRulesInBlocks, ((ColoredValidatorConfig) config).isEnforceColorRulesInBlocks());
    }

    @Test
    public void testValidBlockchainConfig() throws Exception {
        assertConfig("blockchain: {chain: production}", true, false);
        assertConfig("blockchain: {chain: regtest}", true, true);
        assertConfig("blockchain: {chain: testnet3}", true, true);
    }


    @Test
    public void testInvalidLevelDBConfig() throws Exception {
        missingKeys((k) -> {
                    if ("database".equals(k)) return "datadir";
                    else return 100;
                },
                "database", "cacheSize").forEach(badConfig -> {
            ConfigObject config = ConfigValueFactory.fromMap(Collections.emptyMap()).withValue("leveldb", badConfig);
            expectConfigError(Missing.class, () -> new LevelDBStoreFactory(config.toConfig()));
        });

        expectConfigError(WrongType.class, () -> new LevelDBStoreFactory(asConf("database: data, cacheSize: abc")));
    }

    @Test
    public void testGoodLevelDBConfig() throws Exception {
        LevelDBStore store = new LevelDBStoreFactory(asConf("database: data, cacheSize: 100")).get();
        assertNotNull(store);

        PersistentBlocksFactory persistentBlocksFactory =
                new PersistentBlocksFactory(asConfWithReference("hyperledger { store { leveldb { database: data } } } "));
        assertNotNull(persistentBlocksFactory.storeFactory);
        assertTrue(persistentBlocksFactory.storeFactory instanceof LevelDBStoreFactory);
    }

    @Test
    public void testInvalidStoreConfig() throws Exception {
        expectConfigError(BadValue.class, () -> new PersistentBlocksFactory(asConf("")));
        expectConfigError(BadValue.class, () -> new PersistentBlocksFactory(asConfWithReference("hyperledger { store { some: value } }")));
        expectConfigError(BadValue.class, () -> new PersistentBlocksFactory(asConf("store { memory: true, leveldb: { database: data, cacheSize: 100 } }")));
    }

    @Test
    public void testValidStoreConfig() throws Exception {
        PersistentBlocks pblocks = new PersistentBlocksFactory(asConf("store { memory: true }")).get();
        assertNotNull(pblocks);

        pblocks = new PersistentBlocksFactory(asConf("store { leveldb: { database: data, cacheSize: 100 } }")).get();
        assertNotNull(pblocks);
    }

    @Test
    public void testPruneSettingsDisabled() throws Exception {
        assertFalse(new PrunerSettingsFactory(asConfWithReference("")).get().isEnabled());
        assertFalse(new PrunerSettingsFactory(asConfWithReference("hyperledger { store { pruning: { enabled: false }}}")).get().isEnabled());

        assertFalse(new PrunerSettingsFactory(asConfWithReference("hyperledger { store {pruning: { }}}")).get().isEnabled());
        assertTrue(new PrunerSettingsFactory(asConfWithReference("hyperledger { store { pruning: { enabled: true }}}")).get().isEnabled());
    }

    @Test
    public void testInvalidPruneSettings() throws Exception {
        expectConfigError(Missing.class, () -> new PrunerSettingsFactory(asConf("{ pruning: {} }")));

        missingKeys((s) -> 1,
                "pruneAfterEvery",
                "pruneOnlyLowerThanHeight",
                "doNotPruneTopBlocks",
                "pruneFrom").forEach((badConfig) -> {
            ConfigObject config = ConfigValueFactory.fromMap(Collections.emptyMap()).withValue("pruning", badConfig);
            expectConfigError(Missing.class, () -> new PrunerSettingsFactory(config.toConfig()));
        });
    }

    @Test
    public void testValidPruneSettings() throws Exception {
        assertFalse(new PrunerSettingsFactory(asConfWithReference("")).get().isEnabled());
        assertFalse(new PrunerSettingsFactory(asConfWithReference("hyperledger { store { pruning { enabled: false }}}")).get().isEnabled());

        String gc = "hyperledger {pruning { pruneAfterEvery: 1," +
                "pruneOnlyLowerThanHeight: 1, " +
                "doNotPruneTopBlocks: 1," +
                "pruneFrom: 1" +
                "} }";

        PrunerSettingsFactory settings = new PrunerSettingsFactory(asConfWithReference(gc));
        assertNotNull(settings.get());
    }
}
