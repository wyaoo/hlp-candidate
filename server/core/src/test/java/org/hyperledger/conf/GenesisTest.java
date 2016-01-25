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
package org.hyperledger.conf;

import org.hyperledger.common.BID;
import org.hyperledger.core.bitcoin.GenesisBlocks;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class GenesisTest {
    @Test
    public void productionChainTest() {
        assertEquals(new BID("000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f"),
                GenesisBlocks.satoshi.getID());
    }

    @Test
    public void testChainTest() {
        assertEquals(new BID("000000000933ea01ad0ee984209779baaec3ced90fa3f408719526f8d77f4943"),
                GenesisBlocks.testnet3.getID());
    }

    @Test
    public void regtestChainTest() {
        assertEquals(new BID("0f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206"),
                GenesisBlocks.regtest.getID());
    }
}
