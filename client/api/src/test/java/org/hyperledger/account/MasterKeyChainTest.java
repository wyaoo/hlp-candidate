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

package org.hyperledger.account;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.hyperledger.common.*;
import org.junit.Before;
import org.junit.Test;

import java.security.Security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MasterKeyChainTest {

    // class under test
    private MasterKeyChain<PublicKey> chain;

    MasterPrivateKey masterPrivateKey = MasterPrivateKey.createNew();
    MasterPublicKey masterPublicKey = masterPrivateKey.getMasterPublic();
    private static final int LOOKAHEAD = 1;

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    @Before
    public void setUp() throws HyperLedgerException {
        chain = new MasterKeyChain<>(masterPublicKey, LOOKAHEAD);
    }

    private void assertNrOfKeys(int num) {
        int nrOfKeys = chain.getRelevantAddresses().size();
        assertEquals("Incorrect number of keys generated", num, nrOfKeys);
    }

    @Test
    public void firstGetNextKeyReturnsFirstKey() throws HyperLedgerException {
        PublicKey nextKey = chain.getNextKey();
        assertEquals(masterPublicKey.getKey(0), nextKey);
    }

    @Test
    public void lookaheadComputedAfterGetNextKey() throws HyperLedgerException {
        chain.getNextKey();
        Address nextAddress = masterPublicKey.getKey(1).getAddress();
        assertTrue(chain.getRelevantAddresses().contains(nextAddress));
    }

    @Test
    public void lookaheadComputedAfterGetKey() throws HyperLedgerException {
        PublicKey publicKey = chain.getKey(5);
        assertEquals(masterPublicKey.getKey(5), publicKey);

        Address nextAddress = masterPublicKey.getKey(5 + LOOKAHEAD).getAddress();
        assertTrue(chain.getRelevantAddresses().contains(nextAddress));
        assertNrOfKeys(5 + 1 + LOOKAHEAD); // +1, because after getting the 5th key, we already have 6
    }

    @Test
    public void addressTranslatedBackToKeyId() throws HyperLedgerException {
        chain.getNextKey();
        Address address = masterPublicKey.getKey(0).getAddress();
        assertEquals(0, chain.getKeyIDForAddress(address).intValue());
    }

    @Test
    public void lookaheadInitializedLazy() {
        assertNrOfKeys(0);
    }
}
