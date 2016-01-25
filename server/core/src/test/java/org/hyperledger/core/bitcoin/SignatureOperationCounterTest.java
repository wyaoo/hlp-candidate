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


import com.google.common.io.ByteStreams;
import org.hyperledger.common.*;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

public class SignatureOperationCounterTest {

    private SignaturOperationCounter sigOpCount = new SignaturOperationCounter(true, null, (map, in) -> true);

    @Test
    public void sigOpcountTest() throws IOException, HyperLedgerException {
        byte[] binaryText = ByteStreams.toByteArray(getClass().getResourceAsStream("/many_signatures.hexblock"));
        String data = new String(binaryText, StandardCharsets.US_ASCII);
        Block block = Block.fromWireDump(data, WireFormatter.bitcoin, BitcoinHeader.class);

        for (Transaction t : block.getTransactions()) {
            sigOpCount.addSignatureOperationCount(t);
        }

        assertEquals(sigOpCount.getTotal(), 17285);
    }

}
