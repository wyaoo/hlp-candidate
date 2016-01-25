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
package org.hyperledger.blockchainvalidator;

import org.hyperledger.common.BID;
import org.hyperledger.common.BitcoinHeader;
import org.hyperledger.core.StoredHeader;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class ValidatorTest {

    Map<BID, StoredHeader> blockHeaders;

    @Before
    public void setupHeaders() throws IllegalAccessException {
        blockHeaders = new HashMap<>();

        /**
         * Each line contains a block header chain, hashpointers pointing from left to right.
         * So leftmost '.' characters are top headers, rightmost ones are genesis block header.
         * Number characters are hashpointers to items in previous chains.
         *
         *
         * '.': a StoredHeader with previousHash pointing to the StoredHeader right to it.
         * number: a StoredHeader with previousHash pointing to the StoredHeader in the
         *   number'th line. It should refer only to previous lines. Linenumbering is zero based.
         * ' ': used for padding
         */
        List<StoredHeader> headers = createHeaders(
                "...",
                " .0",
                "...."
        );

        for (StoredHeader h : headers) {
            blockHeaders.put(h.getID(), h);
        }
    }

    private void printHeaders(List<StoredHeader> headers, int length) {
        for (StoredHeader h : headers) {
            System.out.println(h.getID().toString().substring(0, length) + " -> " + h.getPreviousID().toString().substring(0, length));
        }
    }

    public List<StoredHeader> createHeaders(String... headers) throws IllegalAccessException {
        StoredHeader prevStoredHeaders[] = new StoredHeader[headers.length];
        ArrayList<StoredHeader> allHeaders = new ArrayList<>();
        for (int i = maxLength(headers) - 1; i >= 0; i--) {
            for (int headerIndex = 0; headerIndex < headers.length; headerIndex++) {
                char c = getaChar(headers[headerIndex], i);
                if (c == '.') {
                    StoredHeader prevStoredHeader = prevStoredHeaders[headerIndex];
                    StoredHeader storedHeader = new StoredHeader(BitcoinHeader.create().previousID(getBlockIdOrInvalidId(prevStoredHeader)).nonce(allHeaders.size()).build(), 1.0, 1);
                    allHeaders.add(storedHeader);
                    prevStoredHeaders[headerIndex] = storedHeader;
                } else if ('0' <= c && c <= '9') {

                    // create new header and link to the given chain
                    StoredHeader prevStoredHeader = prevStoredHeaders[c - '0'];
                    StoredHeader storedHeader = new StoredHeader(BitcoinHeader.create().previousID(getBlockIdOrInvalidId(prevStoredHeader)).nonce(allHeaders.size()).build(), 1.0, 1);
                    allHeaders.add(storedHeader);
                    prevStoredHeaders[headerIndex] = storedHeader;
                } else if (c != ' ') {
                    throw new IllegalAccessException("Unsupported character at: [" + i + ", " + headerIndex + "]: " + c);
                }
            }
        }

        return allHeaders;
    }

    private BID getBlockIdOrInvalidId(StoredHeader header) {
        if (header != null) {
            return header.getID();
        } else {
            return BID.INVALID;
        }
    }

    private char getaChar(String s, int i) {
        if (s.length() > i) {
            return s.charAt(i);
        } else {
            return ' ';
        }
    }

    private int maxLength(String[] headers) {
        int l = 0;
        for (String s : headers) {
            int sl = s.length();
            if (sl > l) {
                l = sl;
            }
        }
        return l;
    }

    @Test
    public void testCheckIfSingleTree() {
        Validator validator = new Validator(null, blockHeaders, 0, 1, 1, 1);
        List<Validator.HeaderChain> chains = validator.checkIfSingleTree();
        org.junit.Assert.assertEquals(3, chains.size());
        org.junit.Assert.assertEquals(4, chains.get(0).height);
        org.junit.Assert.assertEquals(3, chains.get(1).height);
        org.junit.Assert.assertEquals(3, chains.get(2).height);
        assertFalse(chains.get(0).topHeaderId.equals(chains.get(1).topHeaderId));
        assertFalse(chains.get(1).topHeaderId.equals(chains.get(2).topHeaderId));
        assertFalse(chains.get(2).topHeaderId.equals(chains.get(0).topHeaderId));

        assertTrue(chains.get(1).genesisHeaderId.equals(chains.get(2).genesisHeaderId));
        assertFalse(chains.get(0).genesisHeaderId.equals(chains.get(1).genesisHeaderId));

    }

    @Test
    public void testCalculateReward() {
        Validator validator = new Validator(null, blockHeaders, 0, 1, 1, 1);
        assertEquals(5000000000L, validator.calculateReward(0));
        assertEquals(5000000000L, validator.calculateReward(1));
        assertEquals(5000000000L, validator.calculateReward(209999));
        assertEquals(2500000000L, validator.calculateReward(210000));
        assertEquals(2500000000L, validator.calculateReward(419999));
        assertEquals(1250000000L, validator.calculateReward(420000));
        assertEquals(1250000000L, validator.calculateReward(629999));
        assertEquals(625000000L, validator.calculateReward(630000));
    }
}
