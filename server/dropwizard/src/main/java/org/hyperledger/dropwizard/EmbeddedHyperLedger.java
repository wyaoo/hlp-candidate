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
package org.hyperledger.dropwizard;

import akka.actor.ActorSystem;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.hyperledger.api.BCSAPI;
import org.hyperledger.connector.BCSAPIClient;
import org.hyperledger.connector.ConnectorFactory;
import org.hyperledger.connector.InMemoryConnectorFactory;
import org.hyperledger.core.conf.CoreAssembly;
import org.hyperledger.main.BCSAPIServer;
import org.hyperledger.network.HyperLedgerExtension;

public class EmbeddedHyperLedger implements HyperLedgerConfiguration {

    @JsonProperty("embedded")
    private Config config;

    @JsonCreator
    public EmbeddedHyperLedger(@JsonProperty("embedded") Config config) {
        this.config = config;
    }

    @Override
    public ManagedBCSAPI createBCSAPI() {
        Config fullConfig = ConfigFactory.empty()
                .withValue("hyperledger", config.root())
                .withFallback(ConfigFactory.load("akka-base-for-dropwizard"));
        ConnectorFactory factory = new InMemoryConnectorFactory();
        return new ManagedBCSAPI() {
            public BCSAPIClient client = new BCSAPIClient(factory);

            public ActorSystem system;
            private BCSAPIServer server;

            @Override
            public BCSAPI getBCSAPI() {
                return client;
            }

            @Override
            public void start() throws Exception {
                system = ActorSystem.create("EmbeddedHyperLedger", fullConfig);
                HyperLedgerExtension hyperLedger = HyperLedgerExtension.get(system);
                hyperLedger.initialize();

                CoreAssembly core = hyperLedger.coreAssembly();

                server = new BCSAPIServer(core.getBlockStore(), core.getClientEventQueue(), factory);
                server.init();

                client.init();
            }

            @Override
            public void stop() throws Exception {
                client.destroy();
                server.destroy();
                system.shutdown();
            }
        };
    }
}
