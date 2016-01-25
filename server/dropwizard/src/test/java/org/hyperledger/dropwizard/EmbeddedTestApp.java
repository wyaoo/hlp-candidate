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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.typesafe.config.ConfigFactory;
import io.dropwizard.Application;
import io.dropwizard.Configuration;
import io.dropwizard.client.JerseyClientBuilder;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit.DropwizardAppRule;
import org.hyperledger.api.BCSAPI;
import org.hyperledger.test.RegtestRule;
import org.junit.ClassRule;
import org.junit.Test;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.client.Client;
import javax.ws.rs.core.Response;

import static org.junit.Assert.assertEquals;


public class EmbeddedTestApp {

    @ClassRule
    public static RegtestRule rule = new RegtestRule(ConfigFactory.parseResources("test-config.json"));

    @ClassRule
    public static final DropwizardAppRule<TestConfiguration> RULE =
            new DropwizardAppRule<>(MyApp.class, ResourceHelpers.resourceFilePath("test-config.yml"));

    @Test
    public void loginHandlerRedirectsAfterPost() {
        Client client = new JerseyClientBuilder(RULE.getEnvironment()).build("test client");

        Response response = client.target(
                String.format("http://localhost:%d/", RULE.getLocalPort()))
                .request()
                .get();

        assertEquals(200, response.getStatus());
    }

    @Test
    public void mineBlocksTest() {
        rule.getTestServer().mineBlocks(5);
    }

    public static class TestConfiguration extends Configuration {
        @JsonProperty
        private EmbeddedHyperLedger hyperLedger;

        public HyperLedgerConfiguration getHyperLedger() {
            return hyperLedger;
        }
    }

    public static class MyApp extends Application<TestConfiguration> {

        HyperLedgerBundle<TestConfiguration> hyperLedgerBundle;

        @Override
        public void run(TestConfiguration testConfiguration, Environment environment) throws Exception {
            BCSAPI bcsapi = hyperLedgerBundle.getBCSAPI();
            environment.jersey().register(new RootResource(bcsapi));
        }

        @Override
        public void initialize(Bootstrap<TestConfiguration> bootstrap) {
            hyperLedgerBundle = new HyperLedgerBundle<TestConfiguration>() {
                @Override
                protected HyperLedgerConfiguration getSupernodeConfiguration(TestConfiguration configuration) {
                    return configuration.getHyperLedger();
                }
            };
            bootstrap.addBundle(hyperLedgerBundle);
        }
    }

    @Path("/")
    public static class RootResource {

        private BCSAPI bcsapi;

        public RootResource(BCSAPI bcsapi) {
            this.bcsapi = bcsapi;
        }

        @GET
        public String index() {
            return "Hello world";
        }
    }
}
