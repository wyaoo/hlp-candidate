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
package org.hyperledger.dropwizard.activemq;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.hibernate.validator.constraints.NotEmpty;
import org.hyperledger.api.BCSAPI;
import org.hyperledger.connector.BCSAPIClient;
import org.hyperledger.dropwizard.HyperLedgerConfiguration;
import org.hyperledger.dropwizard.ManagedBCSAPI;
import org.hyperledger.jms.JMSConnectorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JMSConnectedHyperLedger implements HyperLedgerConfiguration {
    private static final Logger log = LoggerFactory.getLogger(JMSConnectedHyperLedger.class);

    @JsonProperty
    @NotEmpty
    private String brokerUrl;

    @JsonProperty
    private String username;

    @JsonProperty
    private String password;

    @Override
    public ManagedBCSAPI createBCSAPI() {
        return new ManagedBCSAPI() {
            BCSAPIClient api;// = new BCSAPIClient ();

            @Override
            public void start() throws Exception {
                log.info("Connecting to HyperLedger via JMS " + brokerUrl);
                api = new BCSAPIClient(new JMSConnectorFactory(username, password, brokerUrl));
                api.init();
            }

            @Override
            public void stop() throws Exception {
            }

            @Override
            public BCSAPI getBCSAPI() {
                return api;
            }
        };
    }

    public String getBrokerUrl() {
        return brokerUrl;
    }

    public void setBrokerUrl(String brokerUrl) {
        this.brokerUrl = brokerUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
