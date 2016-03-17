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
package org.hyperledger.accountimport;

import org.hyperledger.common.HyperLedgerException;
import org.hyperledger.common.MasterPrivateKey;
import org.hyperledger.common.MasterPublicKey;
import org.junit.Test;

import java.io.*;

import static org.junit.Assert.assertEquals;

/**
 * Tests the key pair generation implemented in {@link KeyPairGenerator}
 */
public class KeyPairGeneratorTest {

    private static final int GENERATED_COUNT = 500;

    @Test
    public void testMultipleSubsequentGenerations() throws IOException, HyperLedgerException {
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        final KeyPairGenerator generator = createGenerator(outputStream);
        // multiple invocations should also work
        generator.generate(GENERATED_COUNT);
        generator.generate(GENERATED_COUNT);
        outputStream.close();
        final int rowCount = checkGeneratedPairs(outputStream.toByteArray());
        // we made 2 generation rounds
        assertEquals(GENERATED_COUNT * 2, rowCount / 2);
    }

    @Test
    public void testMultipleSubsequentSyncGenerations() throws IOException, HyperLedgerException {
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        final KeyPairGenerator generator = createGenerator(outputStream);
        // multiple invocations should also work
        generator.generateSync(GENERATED_COUNT);
        generator.generateSync(GENERATED_COUNT);
        outputStream.close();
        final int rowCount = checkGeneratedPairs(outputStream.toByteArray());
        // we made 2 generation rounds
        assertEquals(GENERATED_COUNT * 2, rowCount / 2);
    }

    private KeyPairGenerator createGenerator(final OutputStream outputStream) {

        return new KeyPairGenerator(3, outputStream);
    }

    private int checkGeneratedPairs(final byte[] data) throws IOException, HyperLedgerException {
        final BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(data)));
        String line = null;
        // even numbers represent a private key, odd numbers the corresponding
        // public key
        int rowCount = 0;
        while ((line = reader.readLine()) != null) {
            if (rowCount++ % 2 == 0) {
                // should be a private key
                MasterPrivateKey.parse(line);
            } else {
                // should be a public key
                MasterPublicKey.parse(line);
            }
        }
        return rowCount;
    }
}
