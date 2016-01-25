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
import com.google.common.collect.Lists;
import com.google.common.collect.Queues;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.dropwizard.Application;
import io.dropwizard.Configuration;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.hyperledger.account.*;
import org.hyperledger.account.color.ColoredBaseAccount;
import org.hyperledger.account.color.ColoredCoinBucket;
import org.hyperledger.account.color.ColoredTransactionProposal;
import org.hyperledger.api.*;
import org.hyperledger.common.*;
import org.hyperledger.common.color.Color;
import org.hyperledger.common.color.ColoredTransactionOutput;
import org.hyperledger.common.color.NativeAsset;
import org.hyperledger.connector.BCSAPIClient;
import org.hyperledger.connector.ConnectorFactory;
import org.hyperledger.main.BCSAPIServer;
import org.hyperledger.main.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import java.io.File;
import java.io.IOException;
import java.security.Security;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A standalone HyperLedger / Dropwizard test server without bitcoind.
 * <p>
 * Hit the following URLs:
 * <p>
 * http://localhost:8080/mine - this will mine the next block.  First time it will mine 120 blocks, which takes 2 minutes.
 * http://localhost:8080/spend - performs a test spend
 * http://localhost:8080/ - status, not yet populated
 */

public class TestServer {
    private static BCSAPI bcsapi;

    private static Logger log = LoggerFactory.getLogger("TestServer");

    public static void main(String[] args) throws Exception {
        new MyApp().run(args);
    }

    @Path("/")
    public static class RootResource {
        private final BaseAccount minerAccount;
        private final MasterPrivateChain minerChain;
        private final ColoredBaseAccount userAccount;
        private final MasterPrivateChain userChain;
        private final ConfirmationManager confirmations;
        Queue<Transaction> mined = Queues.newArrayDeque();
        BlockingQueue<APIBlock> blocks = Queues.newArrayBlockingQueue(1);
        AtomicLong height = new AtomicLong(0);

        public RootResource() throws HyperLedgerException, BCSAPIException {
            minerChain = new MasterPrivateChain(MasterPrivateKey.createNew());
            minerAccount = new BaseAccount(minerChain);
            userChain = new MasterPrivateChain(MasterPrivateKey.createNew());
            userAccount = new ColoredBaseAccount(userChain);
            //minerAccount.addAccountListener((a, t) -> log.info("new tx {}", t));
            bcsapi.registerTrunkListener(bs -> {
                height.addAndGet(bs.size());
                blocks.addAll(bs);
            });
            confirmations = new ConfirmationManager();
            confirmations.init(bcsapi, 1);
            registerAccount(minerAccount);
            registerAccount(userAccount);
        }

        public void registerAccount(ReadOnlyAccount account) throws BCSAPIException {
            bcsapi.registerTransactionListener(account);
            bcsapi.registerRejectListener(account);
            confirmations.addConfirmationListener(account);
        }

        @GET
        public String index() {
            return "Hello world";
        }

        @GET
        @Path("mine")
        public String mine() throws BCSAPIException, HyperLedgerException, InterruptedException {
            do {
                APIHeader header = bcsapi.mine(minerChain.getNextReceiverAddress());
                APIBlock block = blocks.take();
                mined.add(block.getTransaction(0));
                log.info("mined {}, @{}, have {}", header.getID(), height, mined.size());
                while (mined.size() > 120) {
                    log.info("getting funds");
                    Transaction inTx = mined.poll();
                    Coin coin = inTx.getCoin(0);
                    long value = coin.getOutput().getValue();
                    TransactionProposal proposal =
                            new TransactionProposal(
                                    Lists.newArrayList(coin),
                                    Lists.newArrayList(ColoredTransactionOutput.create()
                                            .payTo(userChain.getNextReceiverAddress())
                                            .value(value - 10000)
                                            .color(Color.BITCOIN)
                                            .build()));
                    Transaction signed = proposal.sign(minerAccount);

                    bcsapi.sendTransaction(signed);
                }
            } while (height.get() < 125);
            return "At height " + height;
        }

        @GET
        @Path("spend")
        public String spend() throws BCSAPIException, HyperLedgerException {
            TransactionProposal proposal = userAccount.createTransactionFactory().propose(userChain.getNextReceiverAddress(), 10000);
            Transaction tx = proposal.sign(userChain.getSigner());
            bcsapi.sendTransaction(tx);
            return "" + tx;
        }

        @GET
        @Path("issue")
        public String issue() throws HyperLedgerException, BCSAPIException, InterruptedException {
            Address address = userChain.getNextReceiverAddress();
            ColoredTransactionOutput out =
                    ColoredTransactionOutput.create().payTo(address).color(new NativeAsset(0)).quantity(10).build();
            ColoredTransactionProposal proposal =
                    userAccount.createTransactionFactory()
                            .proposeColored(PaymentOptions.fixedOutputOrder, Lists.newArrayList(out));
            Transaction tx = proposal.sign(userChain);
            bcsapi.sendTransaction(tx);
            userAccount.process(new APITransaction(tx, null)); // supply it early
            Color color = new NativeAsset(tx.getID(), 0);
            ColoredCoinBucket coins = userAccount.getCoins(color);
            log.info("got {} coins", coins.getTotalQuantity());
            return ByteUtils.toHex(color.getEncoded());
        }

        @GET
        @Path("xfer")
        public String xfer(@QueryParam("color") String colorHex) throws IOException, HyperLedgerException, BCSAPIException {
            Color color = NativeAsset.fromEncoded(ByteUtils.fromHex(colorHex));
            Address address = userChain.getNextReceiverAddress();
            ColoredTransactionProposal proposal =
                    userAccount.createTransactionFactory()
                            .proposeColored(address, color, 5);
            Transaction tx = proposal.sign(userChain);
            bcsapi.sendTransaction(tx);
            userAccount.process(new APITransaction(tx, null)); // supply it early
            ColoredCoinBucket coins = userAccount.getCoins(color);
            log.info("got {} coins", coins.getTotalQuantity());
            return tx.toString();
        }
    }

    public static class TestConfiguration extends Configuration {
    }

    public static class MyApp extends Application<TestConfiguration> {

        public BCSAPI createBCSAPI(Config config) throws BCSAPIException {
            Security.addProvider(new BouncyCastleProvider());

            ActorSystem system = ActorSystem.create("middleware", config);

            BCSAPIServer server = Main.createAndStartServer(system);
            ConnectorFactory connectorFactory = server.getConnectorFactory();

            log.info("Waiting for HyperLedger server API to become available.");

            BCSAPIClient bcsapi = new BCSAPIClient(connectorFactory);
            bcsapi.init();

            log.info("HyperLedger server API is up and running, ready to be used.");

            TransactionListener listener = t -> log.info("new transaction {}", t);
            bcsapi.registerTransactionListener(listener);
            bcsapi.registerTrunkListener(t -> t.forEach(b -> log.info("new trunk {} ntx={}", b.toString(), b.getTransactions().size())));

            return bcsapi;
        }

        @Override
        public void run(TestConfiguration testConfiguration, Environment environment) throws Exception {
            Config config = ConfigFactory.load(ConfigFactory.parseFile(new File("server/dropwizard/config/test.conf"))).resolve();
            bcsapi = createBCSAPI(config);
            RootResource root = new RootResource();
            environment.jersey().register(root);
            root.mine(); // Mine initial blocks so we can do spends right away
        }

        @Override
        public void initialize(Bootstrap<TestConfiguration> bootstrap) {
        }
    }
}
