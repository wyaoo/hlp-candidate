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

import org.fusesource.leveldbjni.JniDBFactory;
import org.hyperledger.common.HyperLedgerException;
import org.iq80.leveldb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.fusesource.leveldbjni.JniDBFactory.factory;

public class LevelDBStore implements OrderedMapStore {
    private static final Logger log = LoggerFactory.getLogger(LevelDBStore.class);

    private static int MEMPOOL = 1024 * 512;

    private String database = "data";

    private long cacheSize = 100 * 1048576;

    private static class BatchContext {
        public TreeMap<byte[], byte[]> cache;
        public WriteBatch batch;
    }

    private static ThreadLocal<BatchContext> threadContext = new ThreadLocal<>();
    private DB db;

    public LevelDBStore(String database, long cacheSize) {
        this.database = database;
        this.cacheSize = cacheSize;
    }

    private static BatchContext getBatchContext() {
        BatchContext bcontext = threadContext.get();
        if (bcontext == null)
            threadContext.set(bcontext = new BatchContext());
        return bcontext;
    }

    @Override
    public void put(byte[] key, byte[] data) {
        try {
            JniDBFactory.pushMemoryPool(MEMPOOL);

            BatchContext bcontext = getBatchContext();

            if (bcontext.batch != null) {
                bcontext.batch.put(key, data);
                bcontext.cache.put(key, data);
            } else {
                db.put(key, data);
            }
        } finally {
            JniDBFactory.popMemoryPool();
        }
    }

    @Override
    public void remove(byte[] key) {
        BatchContext bcontext = getBatchContext();

        if (bcontext.batch != null) {
            bcontext.batch.delete(key);
            bcontext.cache.remove(key);
        } else {
            db.delete(key);
        }
    }

    @Override
    public void clearStore() {
    }

    @Override
    public byte[] get(byte[] key) {
        if (key == null) {
            return null;
        }
        BatchContext bcontext = getBatchContext();
        if (bcontext.batch != null) {
            byte[] data = bcontext.cache.get(key);
            if (data != null) {
                return data;
            }
        }
        try {
            JniDBFactory.pushMemoryPool(MEMPOOL);

            return db.get(key);
        } finally {
            JniDBFactory.popMemoryPool();
        }
    }

    @Override
    public void startBatch() {
        BatchContext bcontext = getBatchContext();
        bcontext.batch = db.createWriteBatch();
        bcontext.cache = new TreeMap<>(new OrderedMapStoreKey.KeyComparator());
    }

    @Override
    public void endBatch() {
        BatchContext bcontext = getBatchContext();
        try {
            if (bcontext.batch != null) {
                db.write(bcontext.batch);
                try {
                    bcontext.batch.close();
                } catch (IOException ignored) {
                }
            }
        } finally {
            bcontext.batch = null;
            bcontext.cache = null;
        }
    }

    @Override
    public void cancelBatch() {
        BatchContext bcontext = getBatchContext();
        try {
            bcontext.batch.close();
        } catch (IOException ignored) {
        } finally {
            bcontext.batch = null;
            bcontext.cache = null;
        }
    }

    public void open() {
        Options options = new Options();
        options.cacheSize(cacheSize);
        options.createIfMissing(true);
        options.compressionType(CompressionType.SNAPPY);

        try {
            db = factory.open(new File(database), options);
            log.debug(db.getProperty("leveldb.stats"));
        } catch (IOException e) {
            log.error("Error opening LevelDB ", e);
        }
    }

    // @PreDestroy
    public void close() {
        try {
            log.info("Closing LevelDB");
            db.close();
        } catch (IOException e) {
            log.error("Error closing LevelDB", e);
        }
    }

    @Override
    public byte[] getFloorKey(byte[] key) {
        byte[] fk = null;
        try {
            DBIterator iterator = null;
            try {
                JniDBFactory.pushMemoryPool(MEMPOOL);

                if (db.get(key) != null) {
                    fk = key;
                } else {
                    iterator = db.iterator();
                    iterator.seek(key);
                    if (iterator.hasPrev()) {
                        fk = iterator.prev().getKey();
                    }
                }
            } finally {
                if (iterator != null) {
                    try {
                        iterator.close();
                    } catch (IOException ignored) {
                    }
                }
            }
            // Workaround for a bug in iterator.seek():
            // If we search for a key which is bigger than any key in the db then we will be resulted with a null
            // instead of the greatest key in the db. So let's fetch the last key in the db (which is the greatest
            // as the keys are ordered) and use that as a result. This workaround rarely needs to be executed.
            if (fk == null) {
                iterator = null;
                try {
                    iterator = db.iterator();
                    iterator.seekToLast();
                    // iterator is now in between the penultimate and the last item
                    if (iterator.hasNext()) {
                        byte[] lastKey = iterator.peekNext().getKey();
                        if (OrderedMapStoreKey.compareByteArrays(lastKey, key) <= 0) {
                            fk = lastKey;
                        }
                    }
                } finally {
                    if (iterator != null) {
                        try {
                            iterator.close();
                        } catch (IOException ignored) {
                        }
                    }

                }
            }
            // use the greatest of the keys found in the db and in the cache
            BatchContext bcontext = getBatchContext();
            if (bcontext.cache != null) {
                byte[] bfk = bcontext.cache.floorKey(key);
                if (bfk != null && (fk == null || OrderedMapStoreKey.compareByteArrays(bfk, fk) > 0)) {
                    fk = bfk;
                }
            }
        } finally {
            JniDBFactory.popMemoryPool();
        }
        return fk;
    }

    @Override
    public void forAll(OrderedMapStoreKey.KeyType t, DataProcessor processor) throws HyperLedgerException {

        Set<byte[]> keysInCache = new TreeSet<>(new OrderedMapStoreKey.KeyComparator());

        BatchContext bcontext = getBatchContext();
        if (bcontext.cache != null) {
            for (Map.Entry<byte[], byte[]> entry : bcontext.cache.tailMap(OrderedMapStoreKey.minKey(t)).entrySet()) {
                byte[] key = entry.getKey();
                if (!OrderedMapStoreKey.hasType(t, key)) {
                    break;
                }
                keysInCache.add(key);
                if (!processor.process(key, entry.getValue())) {
                    break;
                }
            }
        }

        DBIterator iterator = db.iterator();
        try {
            JniDBFactory.pushMemoryPool(MEMPOOL);

            iterator.seek(OrderedMapStoreKey.minKey(t));
            while (iterator.hasNext()) {
                Map.Entry<byte[], byte[]> entry = iterator.next();
                if (!OrderedMapStoreKey.hasType(t, entry.getKey())) {
                    break;
                }
                if (!keysInCache.contains(entry.getKey()) && !processor.process(entry.getKey(), entry.getValue())) {
                    break;
                }
            }
        } finally {
            try {
                iterator.close();
            } catch (IOException ignored) {
            }
            JniDBFactory.popMemoryPool();
        }
    }

    @Override
    public void forAll(OrderedMapStoreKey.KeyType t, byte[] partialKey, DataProcessor processor) throws HyperLedgerException {

        Set<byte[]> keysInCache = new TreeSet<>(new OrderedMapStoreKey.KeyComparator());

        BatchContext bcontext = getBatchContext();
        if (bcontext.cache != null) {
            for (Map.Entry<byte[], byte[]> entry : bcontext.cache.tailMap(OrderedMapStoreKey.createKey(t, partialKey)).entrySet()) {
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
                keysInCache.add(key);
                if (!processor.process(key, entry.getValue())) {
                    break;
                }
            }
        }

        DBIterator iterator = db.iterator();
        try {
            JniDBFactory.pushMemoryPool(MEMPOOL);

            iterator.seek(OrderedMapStoreKey.createKey(t, partialKey));
            while (iterator.hasNext()) {
                Map.Entry<byte[], byte[]> entry = iterator.next();
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
                if (!keysInCache.contains(entry.getKey()) && !processor.process(entry.getKey(), entry.getValue())) {
                    break;
                }
            }
        } finally {
            try {
                iterator.close();
            } catch (IOException ignored) {
            }
            JniDBFactory.popMemoryPool();
        }
    }

    @Override
    public boolean isEmpty() {
        try (DBIterator iterator = db.iterator()) {
            iterator.seekToFirst();
            return !iterator.hasNext();
        } catch (IOException ignored) {
            return true;
        }
    }
}
