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
package org.hyperledger.core;

import org.hyperledger.common.Transaction;

public class ValidatedTransaction extends Transaction {
    private long fee;

    public long getFee() {
        return fee;
    }

    public void setFee(long fee) {
        this.fee = fee;
    }


    public ValidatedTransaction(Transaction transaction, long fee) {
        super(transaction.getVersion(), transaction.getLockTime(), transaction.getInputs(), transaction.getOutputs(), transaction.getID());
        this.fee = fee;
    }
}
