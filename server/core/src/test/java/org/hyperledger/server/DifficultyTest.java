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
package org.hyperledger.server;

import org.hyperledger.core.bitcoin.BitcoinProductionValidatorConfig;
import org.junit.Test;

import java.math.BigInteger;

import static org.hyperledger.core.bitcoin.BitcoinDifficulty.getEncodedDifficulty;
import static org.hyperledger.core.bitcoin.BitcoinDifficulty.getTarget;
import static org.junit.Assert.assertTrue;

public class DifficultyTest {
    @Test
    public void targetTest() {
        assertTrue(getTarget(454983370).toString(16).equals("1e7eca000000000000000000000000000000000000000000000000"));
    }

    @Test
    public void compactTargetTest() {
        assertTrue(getEncodedDifficulty(getTarget(454983370)) == 454983370);
        assertTrue(getEncodedDifficulty(new BigInteger("ffff0000000000000000000000000000000000000000000000000000", 16)) == 486604799);
    }

    @Test
    public void nextTargetTest() {
        BitcoinProductionValidatorConfig config = new BitcoinProductionValidatorConfig();
        assertTrue(config.getDifficulty().getNextEncodedDifficulty(841428, 454983370) == 454375064);
    }

    @Test
    public void difficultyTest() {
        BitcoinProductionValidatorConfig config = new BitcoinProductionValidatorConfig();
        assertTrue(config.getDifficulty().getDifficulty(456101533) == 1378.0);
        assertTrue(config.getDifficulty().getDifficulty(486604799) == 1.0);
    }
}
