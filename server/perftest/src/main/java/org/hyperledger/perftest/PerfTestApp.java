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
package org.hyperledger.perftest;

import akka.actor.ActorSystem;
import com.google.common.base.Stopwatch;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.hyperledger.api.BCSAPI;
import org.hyperledger.connector.BCSAPIClient;
import org.hyperledger.core.conf.CoreAssemblyFactory;
import org.hyperledger.main.BCSAPIServer;
import org.hyperledger.main.Main;

import java.security.Security;
import java.util.concurrent.TimeUnit;

public class PerfTestApp {
    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private final BCSAPIServer server;
    private final BCSAPIClient bcsapi;
    private final ActorSystem system;
    private final int rounds;
    private final int count;
    private final int threadCount;

    public static void main(String[] args) throws Exception {
        Stopwatch watch = Stopwatch.createStarted();
        System.setProperty("logback.configurationFile", "logback.xml");
        int rounds = Integer.parseInt(args[2]);
        int count = Integer.parseInt(args[3]);
//        int threadCount = Integer.parseInt(args[4]);
        int threadCount = 1;
        PerfTestApp app = new PerfTestApp(rounds, count, threadCount);
        app.measure(app.bcsapi);
        app.stop();
        watch.stop();
        System.out.println("DONE in " + watch.elapsed(TimeUnit.SECONDS) + " seconds");
        System.exit(0);
    }

    private void stop() {
        bcsapi.destroy();
        system.shutdown();
        system.awaitTermination();
        CoreAssemblyFactory.reset();
    }


    public PerfTestApp(int rounds, int count, int threadCount) throws Exception {
        this.rounds = rounds;
        this.count = count;
        this.threadCount = threadCount;
        Config config = ConfigFactory.load("test-config.conf");
        system = ActorSystem.create("EmbeddedHyperLedger", config);
        server = Main.createAndStartServer(system);
        bcsapi = new BCSAPIClient(server.getConnectorFactory());
        bcsapi.init();
    }

    protected void measure(BCSAPI api) throws Exception {
        SendTransactionThroughputMeasure txMeasure = new SendTransactionThroughputMeasure(api, rounds, count, threadCount);
        System.out.println(txMeasure.resultsToThroughputPerSec(txMeasure.call()));
    }
}
