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
package org.hyperledger.dropwizard.validation;

import org.hyperledger.account.UIAddress;
import org.hyperledger.common.HyperLedgerException;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

public class BitcoinAddressValidator implements ConstraintValidator<BitcoinAddress, String> {
    private int addressFlag;

    @Override
    public void initialize(BitcoinAddress constraintAnnotation) {
        this.addressFlag = constraintAnnotation.addressFlag();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }

        try {
            // TODO There is no way to know if an address string is of specific type.
            // TODO: there is a way now. I do not know what you wanted to check.
            // Network parameter is removed from fromSatoshiStyle.
            UIAddress.fromSatoshiStyle(value);
            return true;
        } catch (HyperLedgerException e) {
            return false;
        }
    }
}
