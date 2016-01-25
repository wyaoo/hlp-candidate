/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hyperledger.core.kvstore;

import com.google.common.io.Files;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;


@RunWith(Parameterized.class)
public class OrderedMapStoreTest {

    static File tempDir = Files.createTempDir();

    byte[] key_0012 = new byte[]{00, 12};
    byte[] key_0018 = new byte[]{00, 18};
    byte[] key_0134 = new byte[]{01, 34};
    byte[] key_0345 = new byte[]{03, 45};

    byte[] data_0123 = new byte[]{01, 23};

    byte[] searchKey_0011 = new byte[]{00, 11};
    byte[] searchKey_0012 = new byte[]{00, 12};
    byte[] searchKey_0013 = new byte[]{00, 13};
    byte[] searchKey_0018 = new byte[]{00, 18};
    byte[] searchKey_0110 = new byte[]{01, 10};
    byte[] searchKey_0134 = new byte[]{01, 34};
    byte[] searchKey_0276 = new byte[]{02, 76};
    byte[] searchKey_0345 = new byte[]{03, 45};
    byte[] searchKey_0358 = new byte[]{03, 58};


    @Parameterized.Parameters
    public static Iterable<Object[]> data() {
        return Arrays.asList(new Object[][]{{new LevelDBStore(tempDir.getAbsolutePath(), 1024000)}, {new MemoryStore()}});
    }

    @Parameterized.Parameter(0)
    public OrderedMapStore db;

    @Before
    public void setup() {
        db.open();
        db.startBatch();
        db.put(key_0012, data_0123);
        db.put(key_0018, data_0123);
        db.put(key_0134, data_0123);
        db.put(key_0345, data_0123);
        db.endBatch();
    }

    @After
    public void tearDownDb() throws IOException {
        db.close();
        if (db instanceof LevelDBStore) deleteDir(tempDir);
    }

    @Test
    public void floorTest() {
        byte[] floorKey = db.getFloorKey(searchKey_0011);
        assertNull(floorKey);

        floorKey = db.getFloorKey(searchKey_0012);
        assertArrayEquals(key_0012, floorKey);

        floorKey = db.getFloorKey(searchKey_0013);
        assertArrayEquals(key_0012, floorKey);

        floorKey = db.getFloorKey(searchKey_0018);
        assertArrayEquals(key_0018, floorKey);

        floorKey = db.getFloorKey(searchKey_0110);
        assertArrayEquals(key_0018, floorKey);

        floorKey = db.getFloorKey(searchKey_0134);
        assertArrayEquals(key_0134, floorKey);

        floorKey = db.getFloorKey(searchKey_0276);
        assertArrayEquals(key_0134, floorKey);

        floorKey = db.getFloorKey(searchKey_0345);
        assertArrayEquals(key_0345, floorKey);

        floorKey = db.getFloorKey(searchKey_0358);
        assertArrayEquals(key_0345, floorKey);
    }

    void deleteDir(File dir) {
        File[] files = tempDir.listFiles();
        for (File f : files) {
            if (f.isFile()) {
                f.delete();
            } else if (f.isDirectory()) {
                deleteDir(f);
            }
        }
        dir.delete();
    }
}