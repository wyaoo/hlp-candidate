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

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.security.Security;

/**
 * Test for {@link MasterChainImporter}
 */
public class MasterChainImporterTest {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    static final MasterChainImporter.MemoryCompacter NOP_COMPACTER = () -> {
        // doing nothing to speed up testing
    };

    @Test
    public void testImportingWithLimitingWithMemoryMeasurement() throws IOException {
        try (final InputStream stream = this.getClass().getResourceAsStream("/40accountpairs.txt")) {
            final MasterChainImporter importer = new MasterChainImporter(stream, 2, NOP_COMPACTER);
            final MasterChainImporter.MeasurementResults statistics = importer.importChain(15, true);
            Assert.assertEquals(15, statistics.timeStatistics.getCount());
            Assert.assertNotEquals(0, statistics.memoryPerChainPair);
        }
    }

    @Test
    public void testImportingWithoutLimitingWithMemoryMeasurement() throws IOException {
        try (final InputStream stream = this.getClass().getResourceAsStream("/40accountpairs.txt")) {
            final MasterChainImporter importer = new MasterChainImporter(stream, 2, NOP_COMPACTER);
            final MasterChainImporter.MeasurementResults statistics = importer.importChain(100, true);
            Assert.assertEquals(20, statistics.timeStatistics.getCount());
            Assert.assertNotEquals(0, statistics.memoryPerChainPair);
        }
    }

    @Test
    public void testImportingWithLimitingWitouthMemoryMeasurement() throws IOException {
        try (final InputStream stream = this.getClass().getResourceAsStream("/40accountpairs.txt")) {
            final MasterChainImporter importer = new MasterChainImporter(stream, 2, NOP_COMPACTER);
            final MasterChainImporter.MeasurementResults statistics = importer.importChain(15, false);
            Assert.assertEquals(15, statistics.timeStatistics.getCount());
            Assert.assertEquals(0, statistics.memoryPerChainPair);
        }
    }

    @Test
    public void testImportingWithoutLimitingWithoutMemoryMeasurement() throws IOException {
        try (final InputStream stream = this.getClass().getResourceAsStream("/40accountpairs.txt")) {
            final MasterChainImporter importer = new MasterChainImporter(stream, 2, NOP_COMPACTER);
            final MasterChainImporter.MeasurementResults statistics = importer.importChain(100, false);
            Assert.assertEquals(20, statistics.timeStatistics.getCount());
            Assert.assertEquals(0, statistics.memoryPerChainPair);
        }
    }

    @Test(expected = RuntimeException.class)
    public void testFailingForInvalidInput() throws IOException {
        try (final InputStream stream = this.getClass().getResourceAsStream("/40accountpairs_invalid.txt")) {
            final MasterChainImporter importer = new MasterChainImporter(stream, 2, NOP_COMPACTER);
            importer.importChain(15, true);
        }
    }
}
