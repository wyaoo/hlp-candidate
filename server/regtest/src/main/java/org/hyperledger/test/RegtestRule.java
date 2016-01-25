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
package org.hyperledger.test;

import akka.actor.ActorSystem;
import com.typesafe.config.Config;
import org.hyperledger.api.BCSAPI;
import org.hyperledger.common.Address;
import org.hyperledger.common.HyperLedgerException;
import org.hyperledger.connector.BCSAPIClient;
import org.hyperledger.core.bitcoin.BitcoinValidatorFactory;
import org.hyperledger.core.conf.CoreAssembly;
import org.hyperledger.core.conf.CoreAssemblyFactory;
import org.hyperledger.main.BCSAPIServer;
import org.hyperledger.main.Main;
import org.hyperledger.network.HyperLedgerExtension;
import org.junit.rules.ExternalResource;

public class RegtestRule extends ExternalResource {
    private BCSAPIClient bcsapi;
    private ActorSystem system;
    private BCSAPIServer server;
    private HyperLedgerRule hyperLedgerRule;
    private final Config config;

    public RegtestRule(Config config) {
        this.config = config;
    }

    public void start() throws HyperLedgerException {
        system = ActorSystem.create("EmbeddedHyperLedger", config);
        server = Main.createAndStartServer(system);
        Address minerAddress = HyperLedgerExtension.get(system).coreAssembly().getMiningConfig().getMinerAddress();
        bcsapi = new BCSAPIClient(server.getConnectorFactory());
        bcsapi.init();
        CoreAssembly coreAssembly = HyperLedgerExtension.get(system).coreAssembly();
        int coinbaseMaturity = coreAssembly.getValidatorChain().getValidatorFactory(BitcoinValidatorFactory.class).getConfig().getCoinbaseMaturity();
        hyperLedgerRule = new HyperLedgerRule(bcsapi, minerAddress, coreAssembly.getBlockStore(), coinbaseMaturity);
    }

    public BCSAPI getBCSAPI() {
        return bcsapi;
    }

    public TestServer getTestServer() {
        return hyperLedgerRule;
    }

    public void stop() {
        bcsapi.destroy();
        server.destroy();
        system.shutdown();
        system.awaitTermination();
        CoreAssemblyFactory.reset();
    }

    @Override
    protected void after() {
        hyperLedgerRule.after();
        stop();
    }

    @Override
    protected void before() throws Throwable {
        start();
        hyperLedgerRule.before();
    }
}
