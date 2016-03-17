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

import org.junit.Assert;
import org.junit.Test;

/**
 * Test for {@link MasterChainImporterApp}
 */
public class MasterChainImporterAppTest {

    @Test
    public void testSuccessfulImportingWithMemoryMeasurement() {
        final MasterChainImporterApp app = new MasterChainImporterApp();
        final String absolutePath = this.getClass().getResource("/40accountpairs.txt").getPath();
        final int exitStatus = app.mainInternal(new String[]{"10", absolutePath, "3", "measureMemory"},
                MasterChainImporterTest.NOP_COMPACTER);
        Assert.assertEquals(MasterChainImporterApp.SUCCESS, exitStatus);
    }

    @Test
    public void testSuccessfulImportingWithoutMemoryMEasurement() {
        final MasterChainImporterApp app = new MasterChainImporterApp();
        final String absolutePath = this.getClass().getResource("/40accountpairs.txt").getPath();
        final int exitStatus = app.mainInternal(new String[]{"10", absolutePath, "3"},
                MasterChainImporterTest.NOP_COMPACTER);
        Assert.assertEquals(MasterChainImporterApp.SUCCESS, exitStatus);
    }

    @Test
    public void testFailingImportingWithInvalidData() {
        final MasterChainImporterApp app = new MasterChainImporterApp();
        final String absolutePath = this.getClass().getResource("/40accountpairs_invalid.txt").getPath();
        final int exitStatus = app.mainInternal(new String[]{"10", absolutePath, "3"},
                MasterChainImporterTest.NOP_COMPACTER);
        Assert.assertEquals(MasterChainImporterApp.RUNTIME_EXCEPTION, exitStatus);
    }

    @Test
    public void testFailingImportingWithInvalidParameters() {
        final MasterChainImporterApp app = new MasterChainImporterApp();
        final String absolutePath = this.getClass().getResource("/40accountpairs.txt").getPath();
        final int exitStatus = app.mainInternal(new String[]{"10", absolutePath, "-3"},
                MasterChainImporterTest.NOP_COMPACTER);
        Assert.assertEquals(MasterChainImporterApp.ILLEGAL_ARGUMENT_EXCEPTION, exitStatus);
    }

}
