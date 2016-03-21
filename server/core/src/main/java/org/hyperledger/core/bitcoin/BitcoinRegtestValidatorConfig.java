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
package org.hyperledger.core.bitcoin;

import org.hyperledger.HyperLedgerSettings;
import org.hyperledger.common.Block;
import org.hyperledger.common.WireFormatter;

import java.math.BigInteger;

public class BitcoinRegtestValidatorConfig extends BitcoinProductionValidatorConfig {
    private final WireFormatter wireFormatter;

    public BitcoinRegtestValidatorConfig() {
        this.wireFormatter = HyperLedgerSettings.getInstance().getTxWireFormatter();
    }

    @Override
    public Block getGenesisBlock() {
        if (wireFormatter.isNativeAssets())
            return GenesisBlocks.regtestNative;
        else
            return GenesisBlocks.regtest;
    }

    @Override
    public BigInteger getMinimumTarget() {
        return BigInteger.valueOf(0x7fffff).shiftLeft(8 * (0x20 - 3));
    }

    @Override
    public long getRewardForHeight(int height) {
        if (height > 4800)
            return 0;
        return (50L * getCoin()) >> (height / 150L);
    }
}
