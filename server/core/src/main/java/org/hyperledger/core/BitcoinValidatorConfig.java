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
package org.hyperledger.core;

import org.hyperledger.common.Block;
import org.hyperledger.core.bitcoin.BitcoinDifficulty;

import java.math.BigInteger;

public interface BitcoinValidatorConfig extends ValidatorConfig {

    enum ScriptEngine {
        BITCOIN_LIBCONSENSUS,
        BITCOIN_JAVA,
    }

    Block getGenesisBlock();

    boolean allowDifficultyMismatch();

    BitcoinDifficulty getDifficulty();

    BigInteger getMinimumTarget();

    int getNewBlockVersion();

    int getReviewBlocks();

    int getTargetBlockTime();

    int getMaxBlockSize();

    int getMaxBlockSigops();

    long getCoin();

    long getMaxMoney();

    int getLocktimeThreshold();

    int getBlocktimeMedianWindow();

    int getBIP16SwitchTime();

    long getMinFee();

    long getMaxFee();

    long getKBFee();

    int getCoinbaseMaturity();

    long getRewardForHeight(int height);

    int getBlockUpgradeMajority();

    int getVotingWindow();

    int getBlockUpgradeMandatory();

    ScriptEngine getScriptEngine();
}
