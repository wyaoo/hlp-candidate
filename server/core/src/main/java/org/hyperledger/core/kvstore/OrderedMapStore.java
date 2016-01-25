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

public interface OrderedMapStore {
    interface DataProcessor {
        boolean process(byte[] key, byte[] data) throws HyperLedgerException;
    }

    void open();

    void close();

    boolean isEmpty();

    void put(byte[] key, byte[] data);

    void remove(byte[] key);

    byte[] get(byte[] key);

    byte[] getFloorKey(byte[] key);

    void startBatch();

    void endBatch();

    void cancelBatch();

    void forAll(OrderedMapStoreKey.KeyType t, DataProcessor processor) throws HyperLedgerException;

    void forAll(OrderedMapStoreKey.KeyType t, byte[] partialKey, DataProcessor processor) throws HyperLedgerException;

    void clearStore();
}
