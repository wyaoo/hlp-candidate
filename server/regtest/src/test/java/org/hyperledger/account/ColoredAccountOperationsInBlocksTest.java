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
package org.hyperledger.account;

import com.typesafe.config.ConfigFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.hyperledger.account.color.ColorIssuer;
import org.hyperledger.account.color.ColoredAccount;
import org.hyperledger.account.color.ColoredBaseAccount;
import org.hyperledger.api.BCSAPI;
import org.hyperledger.common.PrivateKey;
import org.hyperledger.common.Transaction;
import org.hyperledger.common.color.Color;
import org.hyperledger.test.RegtestRule;
import org.hyperledger.test.TestServer;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import java.security.Security;

import static org.hyperledger.account.ActionWaiter.*;
import static org.junit.Assert.assertEquals;

public class ColoredAccountOperationsInBlocksTest {
    @BeforeClass
    public static void init() {
        Security.addProvider(new BouncyCastleProvider());
    }

    @ClassRule
    public static RegtestRule regtestRule = new RegtestRule(ConfigFactory.parseResources("test-config.json"));

    @Test
    public void test() throws Exception {
        BCSAPI bcsapi = regtestRule.getBCSAPI();
        TestServer testServer = regtestRule.getTestServer();

        ConfirmationManager confirmationManager = new ConfirmationManager();
        confirmationManager.init(bcsapi, 101);

        PrivateKey issuerKey = PrivateKey.createNew();
        PrivateKey receiverKey = PrivateKey.createNew();
        BaseAccount issuerAccount = new BaseAccount(new KeyListChain(issuerKey));
        ColoredAccount coloredAccount = new ColoredBaseAccount(new KeyListChain(receiverKey));

        ColorIssuer colorIssuer = new ColorIssuer(issuerKey);

        // listeners
        bcsapi.registerTransactionListener(issuerAccount);
        confirmationManager.addConfirmationListener(issuerAccount);
        bcsapi.registerTransactionListener(coloredAccount);
        confirmationManager.addConfirmationListener(coloredAccount);
        bcsapi.registerTransactionListener(colorIssuer);
        confirmationManager.addConfirmationListener(colorIssuer);

        // fund issuer address
        UIAddress issuerAddress = new UIAddress(UIAddress.Network.TEST, colorIssuer.getFundingAddress());
        ActionWaiter.execute(() -> testServer.sendTo(issuerAddress.toString(), 1000000000), expected(colorIssuer, 2));

        // check balance
        long b = issuerAccount.getCoins().getTotalSatoshis(); // 1000000000
        assertEquals(1000000000, b);
        long b2 = issuerAccount.getConfirmedCoins().getTotalSatoshis(); // 1000000000
        assertEquals(1000000000, b2);

        Color color = colorIssuer.getColor();
        // issue 100 units carried by 50000 satoshis
        Transaction issuing = colorIssuer.issueTokens(
                receiverKey.getAddress(),
                100, 50000,
                BaseTransactionFactory.MINIMUM_FEE);

        ActionWaiter.execute(() -> bcsapi.sendTransaction(issuing), expectedOneOnEach(colorIssuer, coloredAccount, issuerAccount));

        ActionWaiter.execute(() -> testServer.mineOneBlock(), expectedOne(coloredAccount));

        // receiver account received transaction with colored coin
        b = coloredAccount.getConfirmedCoins().getTotalSatoshis(); //50000
        assertEquals(50000, b);
        b2 = coloredAccount.getConfirmedCoins().getColoredCoins().getCoins().size(); // 1
        assertEquals(1, b2);

        long b3 = coloredAccount.getConfirmedCoins().getColoredCoins().getCoins(color).getTotalQuantity();
        assertEquals(100, b3);

        PrivateKey nextOwner = PrivateKey.createNew();
        Transaction transfer = coloredAccount.createTransactionFactory()
                .proposeColored(nextOwner.getAddress(),
                        color, 50)
                .sign(coloredAccount.getChain());

        ActionWaiter.execute(() -> bcsapi.sendTransaction(transfer), expectedOne(coloredAccount));

        ColoredAccount nextOwnerAccount = new ColoredBaseAccount(new KeyListChain(nextOwner));
        ActionWaiter.execute(() -> testServer.mineOneBlock(), expectedOne(coloredAccount));

        ActionWaiter.execute(() -> nextOwnerAccount.sync(bcsapi), expectedOne(nextOwnerAccount));

        assertEquals(50, nextOwnerAccount.getConfirmedCoins().getColoredCoins().getCoins(color).getTotalQuantity());
    }
}
