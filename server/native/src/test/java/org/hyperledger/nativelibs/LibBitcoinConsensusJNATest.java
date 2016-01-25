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
package org.hyperledger.nativelibs;

import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

@Ignore
public class LibBitcoinConsensusJNATest {
    // value is from from interpreter.h of the original bitcoin implementation
    private static final int SCRIPT_VERIFY_P2SH = 1 << 0;
    private static final int SCRIPT_VERIFY_DERSIG = 1 << 2;
    private static final int SCRIPT_VERIFY_SCIV = 1 << 30;

    @Test
    public final void testNativeHelloWorldJNI()
            throws Exception {
        byte[] scriptPubKey = new byte[]{0, 1, 2, 50};
        byte[] txTo = new byte[]{0, 1, 2, 3, -128, -127};
        int nIn = 0;
        long nInValue = 0;
        LibBitcoinConsensusJNA.BitcoinConsensusErrorHolder error = new LibBitcoinConsensusJNA.BitcoinConsensusErrorHolder();
        int flagsVal = SCRIPT_VERIFY_P2SH + SCRIPT_VERIFY_DERSIG;

        Assert.assertFalse(LibBitcoinConsensusJNA.verifyScript(scriptPubKey, txTo, nInValue, nIn, flagsVal, error));
        Assert.assertEquals(LibBitcoinConsensusJNA.BitcoinConsensusErrorCode.TX_DESERIALIZE, error.getErrorCode());

        Assert.assertFalse(LibBitcoinConsensusJNA.verifyScript(scriptPubKey, txTo, nInValue, nIn, flagsVal | SCRIPT_VERIFY_SCIV, error));
        Assert.assertEquals(LibBitcoinConsensusJNA.BitcoinConsensusErrorCode.TX_DESERIALIZE, error.getErrorCode());
    }


}
