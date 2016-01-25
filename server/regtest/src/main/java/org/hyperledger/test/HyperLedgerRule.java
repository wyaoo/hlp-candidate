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

import org.hyperledger.account.*;
import org.hyperledger.api.BCSAPI;
import org.hyperledger.api.BCSAPIException;
import org.hyperledger.common.Address;
import org.hyperledger.common.HyperLedgerException;
import org.hyperledger.common.PrivateKey;
import org.hyperledger.common.Transaction;
import org.hyperledger.core.bitcoin.BitcoinBlockStore;
import org.junit.rules.ExternalResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HyperLedgerRule extends ExternalResource implements TestServer {
    private static final Logger log = LoggerFactory.getLogger(HyperLedgerRule.class);

    private final BaseAccount minerAccount;
    private final BCSAPI bcsapi;
    private final BitcoinBlockStore blockStore;
    private final int coinbaseMaturity;
    private final Address minerAddress;
    private final KeyListChain keyChain;

    public HyperLedgerRule(BCSAPI bcsapi, Address minerAddress, BitcoinBlockStore blockStore, int coinbaseMaturity) throws HyperLedgerException {
        this.bcsapi = bcsapi;
        this.blockStore = blockStore;
        this.coinbaseMaturity = coinbaseMaturity;
        this.minerAddress = minerAddress;
        // the below private key is the origin of the miner address in hte test-config.json, do not change them
        PrivateKey privateKey = PrivateKey.parseWIF("Kxj5wXRXPxVZScsHkK6Dwo2k7enphcW9wWidvZ93wTALHDXjDo2U");
        keyChain = new KeyListChain(privateKey);
        minerAccount = new BaseAccount(keyChain);
    }

    @Override
    public void mineOneBlock() {
        try {
            bcsapi.mine(minerAddress);
            keyChain.sync(bcsapi, minerAccount);
        } catch (BCSAPIException e) {
            log.error("Mining failed with: {}", e);
        }
    }

    @Override
    public void mineBlocks(int nr) {
        for (int i = 0; i < nr; i++) {
            mineOneBlock();
        }
    }

    @Override
    public void sendTo(String address, long amount) {
        try {
            TransactionFactory factory = new CoinbaseAwareBaseTransactionFactory(minerAccount, blockStore, coinbaseMaturity);
            TransactionProposal proposal = factory.propose(UIAddress.fromSatoshiStyle(address).getAddress(), amount);
            Transaction tx = proposal.sign(minerAccount);
            bcsapi.sendTransaction(tx);
            mineOneBlock();
        } catch (HyperLedgerException | BCSAPIException e) {
            log.error("Failed sending amount {} to address {}. Problem was {}", amount, address, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    protected void before() throws Throwable {
        log.info("Mining first 101 blocks");
        mineBlocks(101);
        log.info("Mined first 101 blocks");
    }

    @Override
    protected void after() {
        // nothing to tear down
    }
}
