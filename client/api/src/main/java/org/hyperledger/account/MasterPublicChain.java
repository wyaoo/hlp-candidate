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
package org.hyperledger.account;

import org.hyperledger.api.BCSAPI;
import org.hyperledger.api.BCSAPIException;
import org.hyperledger.api.TransactionListener;
import org.hyperledger.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class MasterPublicChain implements PublicKeyChain {
    private static final Logger log = LoggerFactory.getLogger(MasterPublicChain.class);

    private final Map<Address, Integer> keyIDForAddress = new HashMap<>();
    private final Map<Integer, PublicKey> keyCache = new HashMap<>();
    private final MasterPublicKey master;
    private final int lookAhead;
    private int nextSequence;

    public MasterPublicKey getMaster() {
        return master;
    }

    public MasterPublicChain(MasterPublicKey master) throws HyperLedgerException {
        this(master, 10);
    }

    public MasterPublicChain(MasterPublicKey master, int lookAhead) throws HyperLedgerException {
        this.master = master;
        this.lookAhead = lookAhead;
        ensureLookAhead(0);
    }

    public int getLookAhead() {
        return lookAhead;
    }

    private void ensureLookAhead(int from) throws HyperLedgerException {
        while (keyIDForAddress.size() < (from + lookAhead)) {
            PublicKey key;
            int keyIndex = keyIDForAddress.size();
            key = master.getKey(keyIndex);
            keyIDForAddress.put(key.getAddress(), keyIndex);
            keyCache.put(keyIndex, key);
        }
    }

    public synchronized PublicKey getKey(int i) throws HyperLedgerException {
        ensureLookAhead(i);
        return keyCache.get(i);
    }

    public synchronized void setNextKey(int i) throws HyperLedgerException {
        nextSequence = i;
        ensureLookAhead(nextSequence);
    }

    public synchronized PublicKey getNextKey() throws HyperLedgerException {
        return getKey(nextSequence++);
    }

    public Integer getKeyIDForAddress(Address address) {
        return keyIDForAddress.get(address);
    }

    @Override
    public synchronized PublicKey getKeyForAddress(Address address) {
        Integer keyId = getKeyIDForAddress(address);
        if (keyId == null) {
            return null;
        }
        try {
            return getKey(keyId);
        } catch (HyperLedgerException e) {
            return null;
        }
    }

    @Override
    public void sync(BCSAPI api, TransactionListener txListener) throws BCSAPIException {
        log.trace("Sync nkeys: " + (nextSequence));
        api.scanTransactions(getMaster(), lookAhead, t -> {
            for (TransactionOutput o : t.getOutputs()) {
                Integer thisKey = getKeyIDForAddress(o.getOutputAddress());
                if (thisKey != null) {
                    ensureLookAhead(thisKey);
                    nextSequence = Math.max(nextSequence, thisKey + 1);
                }
            }
            txListener.process(t);
        });
        log.trace("Sync finished with nkeys: " + nextSequence);
    }

    @Override
    public Set<Address> getRelevantAddresses() {
        return Collections.unmodifiableSet(keyIDForAddress.keySet());
    }

    @Override
    public Address getNextChangeAddress() throws HyperLedgerException {
        return getNextKey().getAddress();
    }

    @Override
    public Address getNextReceiverAddress() throws HyperLedgerException {
        return getNextKey().getAddress();
    }
}
