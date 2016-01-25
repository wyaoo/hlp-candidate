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

import org.hyperledger.core.*;

public class BitcoinValidatorFactory extends ValidatorFactory<BitcoinValidatorConfig> {

    private final BitcoinValidatorConfig config;

    private final BitcoinValidator validator;

    public BitcoinValidatorFactory() {
        this(new BitcoinProductionValidatorConfig());
    }

    public BitcoinValidatorFactory(BitcoinValidatorConfig config) {
        this.config = config;
        validator = new BitcoinValidator(config);
    }

    public BitcoinValidatorFactory(BitcoinValidatorConfig config, BitcoinValidator validator) {
        this.config = config;
        this.validator = validator;
    }

    @Override
    public BitcoinValidatorConfig getConfig() {
        return config;
    }

    @Override
    public BlockHeaderValidator getHeaderValidator() {
        return validator;
    }

    @Override
    public BlockBodyValidator getBodyValidator() {
        return validator;
    }

    @Override
    public TransactionValidator getTransactionValidator() {
        return validator;
    }
}
