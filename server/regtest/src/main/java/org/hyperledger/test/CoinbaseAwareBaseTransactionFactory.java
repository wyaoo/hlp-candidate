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

import org.hyperledger.account.BaseTransactionFactory;
import org.hyperledger.account.ReadOnlyAccount;
import org.hyperledger.common.HyperLedgerException;
import org.hyperledger.common.Coin;
import org.hyperledger.core.bitcoin.BitcoinBlockStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class CoinbaseAwareBaseTransactionFactory extends BaseTransactionFactory {
    private static final Logger log = LoggerFactory.getLogger(CoinbaseAwareBaseTransactionFactory.class);

    private BitcoinBlockStore blockStore;
    private int coinbaseMaturity;

    public CoinbaseAwareBaseTransactionFactory(ReadOnlyAccount account, BitcoinBlockStore blockStore, int coinbaseMaturity) {
        super(account);
        this.blockStore = blockStore;
        this.coinbaseMaturity = coinbaseMaturity;
    }

    @Override
    protected List<Coin> filter(List<Coin> coins) {
        List<Coin> remainingCoins = new ArrayList<Coin>();
        for (Coin c : coins) {
            try {
                if (c.getOutpoint().getOutputIndex() != 0 || blockStore.getTransactionHeight(c.getOutpoint().getTransactionId()) <= blockStore.getFullHeight() - coinbaseMaturity) {
                    remainingCoins.add(c);
                }
            } catch (HyperLedgerException e) {
                log.error("Error filtering coinbase coins");
            }
        }
        return remainingCoins;
    }
}
