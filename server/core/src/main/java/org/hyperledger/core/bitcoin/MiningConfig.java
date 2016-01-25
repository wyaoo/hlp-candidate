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
package org.hyperledger.core.bitcoin;

import org.hyperledger.account.UIAddress;
import org.hyperledger.common.Address;
import org.hyperledger.common.HyperLedgerException;
import org.hyperledger.core.ValidatorConfig;

public class MiningConfig implements ValidatorConfig {
    public static final MiningConfig DISABLED;
    private final boolean enabled;
    private final Address minerAddress;
    private final int delayBetweenMiningBlocksSecs;

    public MiningConfig(boolean enabled, String minerAddress, int delayBetweenMiningBlocksSecs) throws HyperLedgerException {
        Address tempMinerAddress;
        this.enabled = enabled;
        this.delayBetweenMiningBlocksSecs = delayBetweenMiningBlocksSecs;
        try {
            tempMinerAddress = UIAddress.fromSatoshiStyle(minerAddress).getAddress();
        } catch (HyperLedgerException e) {
            if (enabled) {
                throw e;
            } else {
                tempMinerAddress = null;
            }
        }
        this.minerAddress = tempMinerAddress;
    }

    public boolean enabled() {
        return enabled;
    }


    public Address getMinerAddress() {
        return minerAddress;
    }

    static {
        MiningConfig config = null;
        try {
            config = new MiningConfig(false, null, 0);
        } catch (HyperLedgerException e) {
            // should not happen, nothing to do
        }
        DISABLED = config;
    }

    public int getDelayBetweenMiningBlocksSecs() {
        return delayBetweenMiningBlocksSecs;
    }
}
