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
import org.hyperledger.api.BCSAPI;
import org.hyperledger.common.Address;
import org.hyperledger.common.MasterPrivateKey;
import org.hyperledger.common.PrivateKey;
import org.hyperledger.common.Transaction;
import org.hyperledger.test.RegtestRule;
import org.hyperledger.test.TestServer;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import java.security.Security;

import static org.hyperledger.account.ActionWaiter.*;
import static org.junit.Assert.assertEquals;

public class AccountOperationsInBlocksTest {
    @BeforeClass
    public static void init() {
        Security.addProvider(new BouncyCastleProvider());
    }

    @ClassRule
    public static RegtestRule regtestRule = new RegtestRule(ConfigFactory.parseResources("test-config.json"));

    @Test
    public void sendToAddressTest() throws Exception {
        BCSAPI api = regtestRule.getBCSAPI();
        TestServer testServer = regtestRule.getTestServer();

        ConfirmationManager confirmationManager = new ConfirmationManager();
        confirmationManager.init(api, 101);

        KeyListChain senderKeyChain = new KeyListChain(PrivateKey.createNew(true));

        UIAddress senderAddress = new UIAddress(UIAddress.Network.TEST, senderKeyChain.getNextReceiverAddress());
        BaseAccount senderAccount = new BaseAccount(senderKeyChain);
        senderAccount.sync(api);
        api.registerTransactionListener(senderAccount);
        confirmationManager.addConfirmationListener(senderAccount);

        ActionWaiter.execute(() -> testServer.sendTo(senderAddress.toString(), 100000000), expected(senderAccount, 2));

        assertEquals("Incorrect balance", 100000000, senderAccount.getCoins().getTotalSatoshis());
        assertEquals("Incorrect confirmed", 100000000, senderAccount.getConfirmedCoins().getTotalSatoshis());

        Address receiverAddress = PrivateKey.createNew().getAddress();
        ReadOnlyAccount receiverAccount = new BaseReadOnlyAccount(new AddressListChain(receiverAddress));
        api.registerTransactionListener(receiverAccount);
        confirmationManager.addConfirmationListener(receiverAccount);

        TransactionFactory factory = new BaseTransactionFactory(senderAccount);

        Transaction tx = factory.propose(receiverAddress, 30000).sign(senderKeyChain);
        ActionWaiter.execute(() -> api.sendTransaction(tx), expectedOneOnEach(senderAccount, receiverAccount));

        assertEquals("Incorrect balance", 30000, receiverAccount.getCoins().getTotalSatoshis());
        assertEquals("Incorrect receiving", 30000, receiverAccount.getReceivingCoins().getTotalSatoshis());
        assertEquals("Incorrect balance", 99965000, senderAccount.getCoins().getTotalSatoshis());
        assertEquals("Incorrect change", 99965000, senderAccount.getChangeCoins().getTotalSatoshis());
        ActionWaiter.execute(() -> testServer.mineOneBlock(), expectedOne(senderAccount));

        assertEquals("Incorrect balance", 99965000, senderAccount.getCoins().getTotalSatoshis());
        assertEquals("Incorrect confirmed", 99965000, senderAccount.getConfirmedCoins().getTotalSatoshis());

        ActionWaiter.execute(() -> receiverAccount.sync(api), expectedOne(receiverAccount));

        assertEquals("Incorrect balance", 30000, receiverAccount.getCoins().getTotalSatoshis());
        assertEquals("Incorrect confirmed", 30000, receiverAccount.getConfirmedCoins().getTotalSatoshis());

        // multisig
        MasterPrivateKey m1 = MasterPrivateKey.createNew();
        MasterPrivateKey m2 = MasterPrivateKey.createNew();

        MultiSigKeyChain multiChain = new MultiSigMasterChain(2, m1, m2);
        BaseTransactionFactory multiCoins = new BaseTransactionFactory(new BaseAccount(multiChain));
        ReadOnlyAccount multiCoinsAccount = multiCoins.getAccount();
        api.registerTransactionListener(multiCoinsAccount);
        confirmationManager.addConfirmationListener(multiCoinsAccount);

        Transaction multiFunding = factory.propose(multiChain.getNextReceiverAddress(), 1000000).sign(senderKeyChain);
        ActionWaiter.execute(() -> api.sendTransaction(multiFunding), expectedOneOnEach(senderAccount, multiCoinsAccount));

        ActionWaiter.execute(() -> testServer.mineOneBlock(), expectedOne(multiCoinsAccount));

        assertEquals(1000000, multiCoinsAccount.getCoins().getTotalSatoshis());

        MasterPrivateKey m3 = MasterPrivateKey.createNew();
        MasterPrivateKey m4 = MasterPrivateKey.createNew();

        MultiSigKeyChain otherChain = new MultiSigMasterChain(2, m3, m4);
        BaseTransactionFactory otherCoins = new BaseTransactionFactory(new BaseAccount(otherChain));
        ReadOnlyAccount otherCoinsAccount = otherCoins.getAccount();
        api.registerTransactionListener(otherCoinsAccount);
        confirmationManager.addConfirmationListener(otherCoinsAccount);

        Transaction toOther = multiCoins.propose(otherChain.getNextReceiverAddress(), 500000).sign(multiChain);

        ActionWaiter.execute(() -> api.sendTransaction(toOther), expectedOne(otherCoinsAccount));

        ActionWaiter.execute(() -> testServer.mineOneBlock(), expectedOne(otherCoinsAccount));

        assertEquals(500000, otherCoinsAccount.getCoins().getTotalSatoshis());
    }
}
