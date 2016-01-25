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
package org.hyperledger.account.color;

import org.hyperledger.account.AddressChain;
import org.hyperledger.account.BaseReadOnlyAccount;
import org.hyperledger.common.Coin;
import org.hyperledger.common.color.Color;

import java.util.List;

public class ColoredBaseReadOnlyAccount extends BaseReadOnlyAccount implements ColoredReadOnlyAccount {
    public ColoredBaseReadOnlyAccount(AddressChain addressChain) {
        super(addressChain);
    }

    public ColoredBaseReadOnlyAccount(AddressChain addressChain, List<Coin> receiving) {
        super(addressChain, receiving);
    }

    @Override
    public ColoredTransactionFactory createTransactionFactory() {
        return new ColoredBaseTransactionFactory(this);
    }


    @Override
    public ColoredCoinBucket getColoredCoins() {
        return getCoins().getColoredCoins();
    }

    @Override
    public ColoredCoinBucket getCoins(Color color) {
        return getColoredCoins().getCoins(color);
    }
}
