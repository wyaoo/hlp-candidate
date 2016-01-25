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
package org.hyperledger.core.signed;

import org.hyperledger.common.HyperLedgerException;
import org.hyperledger.common.ByteUtils;
import org.hyperledger.common.PrivateKey;
import org.hyperledger.common.PublicKey;
import org.hyperledger.core.BitcoinValidatorConfig;
import org.hyperledger.core.ValidatorConfig;

import java.util.ArrayList;
import java.util.List;

import static org.hyperledger.core.BitcoinValidatorConfig.ScriptEngine.BITCOIN_JAVA;

public class BlockSignatureConfig implements ValidatorConfig {
    public static final BlockSignatureConfig DISABLED;
    private final boolean enabled;
    private PrivateKey minerPrivateKey;
    private int requiredSignatureCount;
    private List<PublicKey> publicKeys;
    private int privateKeyIndex;

    public BlockSignatureConfig(boolean enabled, String minerPrivateKeyWIF, int requiredSignatureCount, List<String> publicKeyStrings) throws HyperLedgerException {
        this.enabled = enabled;
        if (enabled) {
            publicKeys = new ArrayList<>(publicKeyStrings.size());
            minerPrivateKey = PrivateKey.parseWIF(minerPrivateKeyWIF);
            PublicKey minerPublicKey = minerPrivateKey.getPublic();
            this.requiredSignatureCount = requiredSignatureCount;
            int counter = 0;
            for (String s : publicKeyStrings) {
                PublicKey publicKey = new PublicKey(ByteUtils.fromHex(s), true);
                publicKeys.add(publicKey);
                if (minerPublicKey.equals(publicKey)) {
                    privateKeyIndex = counter;
                }
                counter++;
            }
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public PrivateKey getMinerPrivateKey() {
        return minerPrivateKey;
    }

    public int getRequiredSignatureCount() {
        return requiredSignatureCount;
    }

    public List<PublicKey> getPublicKeys() {
        return publicKeys;
    }

    public int getPrivateKeyIndex() {
        return privateKeyIndex;
    }


    static {
        BlockSignatureConfig bs = null;
        try {
            bs = new BlockSignatureConfig(false, null, 1, null);
        } catch (HyperLedgerException e) {
            // should not happen, nothing to do
        }
        DISABLED = bs;
    }

    public BitcoinValidatorConfig.ScriptEngine getScriptEngine() {
        return BITCOIN_JAVA;
    }
}
