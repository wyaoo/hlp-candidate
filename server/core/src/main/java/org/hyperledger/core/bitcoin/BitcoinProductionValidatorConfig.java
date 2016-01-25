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

import org.hyperledger.common.Block;
import org.hyperledger.core.BitcoinValidatorConfig;

import java.math.BigInteger;

public class BitcoinProductionValidatorConfig implements BitcoinValidatorConfig {

    @Override
    public BitcoinDifficulty getDifficulty() {
        return new BitcoinDifficulty(getMinimumTarget(), getReviewBlocks(), getTargetBlockTime());
    }

    @Override
    public boolean allowDifficultyMismatch() {
        return false;
    }

    @Override
    public Block getGenesisBlock() {
        return GenesisBlocks.satoshi;
    }

    @Override
    public int getNewBlockVersion() {
        return 3;
    }

    @Override
    public ScriptEngine getScriptEngine() {
        return ScriptEngine.BITCOIN_JAVA;
    }

    @Override
    public long getRewardForHeight(int height) {
        if (height > 6720000)
            return 0;
        return (50L * getCoin()) >> (height / 210000L);
    }

    @Override
    public int getBlockUpgradeMajority() {
        return 750;
    }

    @Override
    public int getVotingWindow() {
        return 1000;
    }

    @Override
    public int getBlockUpgradeMandatory() {
        return 950;
    }

    @Override
    public BigInteger getMinimumTarget() {
        return BigInteger.valueOf(0xFFFFL).shiftLeft(8 * (0x1d - 3));
    }

    @Override
    public int getReviewBlocks() {
        return 2016;
    }

    @Override
    public int getTargetBlockTime() {
        return 1209600;
    }

    @Override
    public int getMaxBlockSize() {
        return 1000000;
    }

    @Override
    public int getMaxBlockSigops() {
        return getMaxBlockSize() / 50;
    }

    @Override
    public long getCoin() {
        return 100000000;
    }

    @Override
    public long getMaxMoney() {
        return 21000000 * getCoin();
    }

    @Override
    public int getLocktimeThreshold() {
        return 500000000;
    }

    @Override
    public int getBlocktimeMedianWindow() {
        return 11;
    }

    @Override
    public int getBIP16SwitchTime() {
        return 1333238400;
    }

    @Override
    public long getMinFee() {
        return 5000;
    }

    @Override
    public long getMaxFee() {
        return getCoin() / 10;
    }

    public long getKBFee() {
        return 1000;
    }

    @Override
    public int getCoinbaseMaturity() {
        return 100;
    }
}
