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

import org.hyperledger.common.MasterKey;
import org.hyperledger.common.MasterPrivateKey;
import org.hyperledger.common.MasterPublicKey;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

import static org.hyperledger.dropwizard.validation.ValidExtendedKey.KeyType.*;

/**
 *
 */
public class ExtendedKeyValidator implements ConstraintValidator<ValidExtendedKey, MasterKey> {
    private ValidExtendedKey.KeyType keyType;

    @Override
    public void initialize(ValidExtendedKey constraintAnnotation) {
        this.keyType = constraintAnnotation.keyType();
    }

    @Override
    public boolean isValid(MasterKey value, ConstraintValidatorContext context) {
        return keyType == ANY ||
                value == null ||
                (keyType == PUBLIC && value instanceof MasterPublicKey) || (keyType == PRIVATE && value instanceof MasterPrivateKey);
    }
}
