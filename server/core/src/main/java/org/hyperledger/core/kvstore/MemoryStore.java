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
package org.hyperledger.core.kvstore;

import org.hyperledger.common.HyperLedgerException;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

public class MemoryStore implements OrderedMapStore {
    @Override
    public void clearStore() {
        db.clear();
        batch = null;
    }

    private final TreeMap<byte[], byte[]> db = new TreeMap<>(new OrderedMapStoreKey.KeyComparator());
    private TreeMap<byte[], byte[]> batch = null;

    @Override
    public synchronized void put(byte[] key, byte[] data) {
        if (batch != null) {
            batch.put(key, data);
        } else {
            db.put(key, data);
        }
    }

    @Override
    public void remove(byte[] key) {
        if (batch != null) {
            batch.remove(key);
        } else {
            db.remove(key);
        }
    }

    @Override
    public synchronized byte[] get(byte[] key) {
        if (key == null) {
            return null;
        }
        if (batch != null) {
            byte[] data = batch.get(key);
            if (data != null) {
                return data;
            }
        }
        byte[] k = db.get(key);
        if (k != null)
            return Arrays.copyOf(k, k.length);
        return null;
    }

    @Override
    public synchronized byte[] getFloorKey(byte[] key) {
        if (key == null) {
            return null;
        }
        byte[] fk = db.floorKey(key);
        if (batch != null) {
            byte[] bfk = batch.floorKey(key);
            if (bfk != null && (fk == null || OrderedMapStoreKey.compareByteArrays(bfk, fk) > 0)) {
                fk = bfk;
            }
        }
        if (fk != null)
            return Arrays.copyOf(fk, fk.length);
        return null;
    }

    @Override
    public synchronized void startBatch() {
        batch = new TreeMap<>(new OrderedMapStoreKey.KeyComparator());
    }

    @Override
    public synchronized void endBatch() {
        if (batch != null) {
            for (Map.Entry<byte[], byte[]> e : batch.entrySet()) {
                db.put(e.getKey(), e.getValue());
            }
            batch = null;
        }
    }

    @Override
    public synchronized void cancelBatch() {
        batch = null;
    }

    @Override
    public void forAll(OrderedMapStoreKey.KeyType t, DataProcessor processor) throws HyperLedgerException {
        for (Map.Entry<byte[], byte[]> entry : db.tailMap(OrderedMapStoreKey.minKey(t)).entrySet()) {
            byte[] key = entry.getKey();
            if (!OrderedMapStoreKey.hasType(t, key)) {
                break;
            }
            if (!processor.process(key, entry.getValue())) {
                break;
            }
        }
    }

    @Override
    public void forAll(OrderedMapStoreKey.KeyType t, byte[] partialKey, DataProcessor processor) throws HyperLedgerException {
        for (Map.Entry<byte[], byte[]> entry : db.tailMap(OrderedMapStoreKey.createKey(t, partialKey)).entrySet()) {
            byte[] key = entry.getKey();
            if (!OrderedMapStoreKey.hasType(t, key)) {
                break;
            }
            boolean found = true;
            for (int i = 0; i < partialKey.length; ++i) {
                if (key[i + 1] != partialKey[i]) {
                    found = false;
                    break;
                }
            }
            if (!found) {
                break;
            }
            if (!processor.process(key, entry.getValue())) {
                break;
            }
        }
    }

    @Override
    public void open() {
    }

    @Override
    public void close() {
    }

    @Override
    public boolean isEmpty() {
        return db.isEmpty();
    }
}
