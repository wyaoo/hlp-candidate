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

import org.hyperledger.account.CoinBucket;
import org.hyperledger.common.Coin;
import org.hyperledger.common.Outpoint;
import org.hyperledger.common.TransactionOutput;
import org.hyperledger.common.color.Color;
import org.hyperledger.common.color.ColoredTransactionOutput;

import java.util.Map;

public class ColoredCoinBucket extends CoinBucket {
    public ColoredCoinBucket(ColoredCoinBucket other) {
        this(new CoinBucket(other));
    }

    @Override
    public synchronized ColoredCoinBucket add(CoinBucket other) {
        if (other instanceof ColoredCoinBucket)
            super.add(other);
        else {
            for (Coin s : other.getCoins()) {
                if (s instanceof ColoredCoin) {
                    add(s);
                } else {
                    add(new ColoredCoin(s.getOutpoint(), new ColoredTransactionOutput(
                            s.getOutput(), Color.BITCOIN, s.getOutput().getValue())));
                }
            }
        }
        return this;
    }

    public synchronized long getTotalQuantity() {
        long s = 0;
        for (TransactionOutput o : coins.values()) {
            if (o instanceof ColoredTransactionOutput)
                s += ((ColoredTransactionOutput) o).getQuantity();
        }
        return s;
    }

    public synchronized ColoredCoinBucket getCoins(Color color) {
        ColoredCoinBucket coloredCoins = new ColoredCoinBucket();
        for (Map.Entry<Outpoint, TransactionOutput> e : coins.entrySet()) {
            TransactionOutput o = e.getValue();
            if (o instanceof ColoredTransactionOutput && ((ColoredTransactionOutput) o).getColor().equals(color)) {
                coloredCoins.add(new ColoredCoin(e.getKey(), (ColoredTransactionOutput) o));

            }
        }
        return coloredCoins;
    }

    public ColoredCoinBucket() {
    }

    public ColoredCoinBucket(CoinBucket coins) {
        for (Coin c : coins.getCoins()) {
            if (c.getOutput() instanceof ColoredTransactionOutput) {
                this.coins.put(c.getOutpoint(), c.getOutput());
            } else {
                this.coins.put(c.getOutpoint(),
                        new ColoredTransactionOutput(c.getOutput().getValue(), c.getOutput().getScript()));
            }
        }
    }
}
