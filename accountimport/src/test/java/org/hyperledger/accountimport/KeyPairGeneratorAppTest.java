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

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;

/**
 * Tests the argument validation aspects of {@link KeyPairGeneratorApp} -
 * the generation itself is tested in {@link KeyPairGeneratorTest}
 */
public class KeyPairGeneratorAppTest {

    KeyPairGeneratorApp app;

    @Before
    public void init() {
        app = new KeyPairGeneratorApp();
    }

    @Test
    public void testParsingValidArguments() {
        final KeyPairGeneratorApp.CommandlineArguments arguments =
                app.checkAndExtractArguments(new String[]{"123", "file.txt", "4"});
        assertEquals(123, arguments.countToGenerate);
        assertEquals("file.txt", arguments.filePath);
        assertEquals(4, arguments.threadCount);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFailingForNegativeCount() {
        app.checkAndExtractArguments(new String[]{"-123", "file.txt", "4"});
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFailingForInvalidCount() {
        app.checkAndExtractArguments(new String[]{"abc", "file.txt", "4"});
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFailingForNegativeThreadCount() {
        app.checkAndExtractArguments(new String[]{"123", "file.txt", "-4"});
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFailingForInvalidThreadCount() {
        app.checkAndExtractArguments(new String[]{"123", "file.txt", "abc"});
    }

    @Test
    public void testPositivePathExecution() throws IOException {
        final File tmpFile = createTmpFile();
        final int exitCode = app.mainInternal(
                new String[]{"123", tmpFile.getAbsolutePath(), "4"});
        assertEquals(KeyPairGeneratorApp.SUCCESS, exitCode);
    }

    @Test
    public void testFailiureBecauseofInvalidCount() throws IOException {
        final File tmpFile = createTmpFile();
        final int exitCode = app.mainInternal(
                new String[]{"-123", tmpFile.getAbsolutePath(), "4"});
        assertEquals(KeyPairGeneratorApp.ILLEGAL_ARGUMENT_EXCEPTION, exitCode);
    }

    @Test
    public void testFailiureBecauseofInvalidThreadCount() throws IOException {
        final File tmpFile = createTmpFile();
        final int exitCode = app.mainInternal(
                new String[]{"123", tmpFile.getAbsolutePath(), "-4"});
        assertEquals(KeyPairGeneratorApp.ILLEGAL_ARGUMENT_EXCEPTION, exitCode);
    }

    private File createTmpFile() throws IOException {
        final File tmpFile = File.createTempFile("test", "test");
        tmpFile.deleteOnExit();
        return tmpFile;
    }
}
