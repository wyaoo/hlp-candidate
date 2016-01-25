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

public class MasterPrivateChain implements KeyChain {
    private static final Logger log = LoggerFactory.getLogger(MasterPrivateChain.class);

    private final Map<Address, Integer> keyIDForAddress = new HashMap<>();
    private final Map<Integer, PrivateKey> keyCache = new HashMap<>();
    private final MasterPrivateKey master;
    private final int lookAhead;
    private final Signer signer;
    private int nextSequence;

    public MasterPrivateKey getMaster() {
        return master;
    }

    public MasterPrivateChain(MasterPrivateKey master) throws HyperLedgerException {
        this(master, 10);
    }

    public MasterPrivateChain(MasterPrivateKey master, int lookAhead) throws HyperLedgerException {
        this.master = master;
        this.lookAhead = lookAhead;
        ensureLookAhead(0);
        signer = new Signer(this);
    }

    @Override
    public Signer getSigner() {
        return signer;
    }

    public int getLookAhead() {
        return lookAhead;
    }

    private void ensureLookAhead(int from) throws HyperLedgerException {
        while (keyIDForAddress.size() < (from + lookAhead)) {
            PrivateKey key;
            int keyIndex = keyIDForAddress.size();
            key = master.getKey(keyIndex);
            keyCache.put(keyIndex, key);
            keyIDForAddress.put(key.getAddress(), keyIndex);
        }
    }

    public synchronized PrivateKey getKey(int i) throws HyperLedgerException {
        ensureLookAhead(i);
        return keyCache.get(i);
    }

    public synchronized void setNextKey(int i) throws HyperLedgerException {
        nextSequence = i;
        ensureLookAhead(nextSequence);
    }

    public synchronized PrivateKey getNextKey() throws HyperLedgerException {
        return getKey(nextSequence++);
    }

    public synchronized Integer getKeyIDForAddress(Address address) {
        return keyIDForAddress.get(address);
    }

    @Override
    public synchronized PrivateKey getKeyForAddress(Address address) {
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
        api.scanTransactions(getMaster().getMasterPublic(), lookAhead, t -> {
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
    public synchronized Set<Address> getRelevantAddresses() {
        return Collections.unmodifiableSet(keyIDForAddress.keySet());
    }

    @Override
    public synchronized Address getNextChangeAddress() throws HyperLedgerException {
        return getNextKey().getAddress();
    }

    @Override
    public synchronized Address getNextReceiverAddress() throws HyperLedgerException {
        return getNextKey().getAddress();
    }
}
