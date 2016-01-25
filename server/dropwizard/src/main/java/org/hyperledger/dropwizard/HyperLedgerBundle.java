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

import com.codahale.metrics.health.HealthCheck;
import io.dropwizard.Configuration;
import io.dropwizard.ConfiguredBundle;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import org.hyperledger.api.BCSAPI;
import org.hyperledger.api.BCSAPIException;
import org.hyperledger.dropwizard.hocon.HoconModule;
import org.hyperledger.jackson.SupernodeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

public abstract class HyperLedgerBundle<T extends Configuration> implements ConfiguredBundle<T> {
    private static final Logger log = LoggerFactory.getLogger(HyperLedgerBundle.class);

    private ManagedBCSAPI managedBCSAPI;

    protected abstract HyperLedgerConfiguration getSupernodeConfiguration(T configuration);

    @Override
    public void run(T configuration, Environment environment) throws Exception {
        final HyperLedgerConfiguration supernode = getSupernodeConfiguration(configuration);

        log.info("Creating BCSAPI instance");
        managedBCSAPI = supernode.createBCSAPI();

        // jackson module for JSON serialization
        environment.getObjectMapper().registerModule(new SupernodeModule(() -> {
            try {
                return getBCSAPI().ping(0) == 0;
            } catch (BCSAPIException e) {
                return false;
            }
        }));

        environment.lifecycle().manage(managedBCSAPI);
        environment.healthChecks().register("supernode", new HealthCheck() {
            @Override
            protected Result check() throws Exception {
                try {
                    managedBCSAPI.getBCSAPI().ping(new Random().nextLong());
                    return Result.healthy("Ping succeeded");
                } catch (BCSAPIException be) {
                    return Result.unhealthy(be);
                }

            }
        });
    }

    public BCSAPI getBCSAPI() {
        return managedBCSAPI.getBCSAPI();
    }

    @Override
    public void initialize(Bootstrap<?> bootstrap) {
        bootstrap.getObjectMapper().registerModule(new HoconModule());
    }
}
