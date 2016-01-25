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
package org.hyperledger.core.signed;

import org.hyperledger.core.BlockHeaderValidator;
import org.hyperledger.core.ValidatorFactory;

public class BlockSignatureValidatorFactory extends ValidatorFactory<BlockSignatureConfig> {
    private BlockSignatureConfig blockSignatureValidatorConfig;
    private BlockSignatureHeaderValidator blockSignatureHeaderValidator;

    public BlockSignatureValidatorFactory(BlockSignatureConfig blockSignatureValidatorConfig) {
        this.blockSignatureValidatorConfig = blockSignatureValidatorConfig;
        blockSignatureHeaderValidator = new BlockSignatureHeaderValidator(blockSignatureValidatorConfig);
    }

    @Override
    public BlockSignatureConfig getConfig() {
        return blockSignatureValidatorConfig;
    }

    @Override
    public BlockHeaderValidator getHeaderValidator() {
        return blockSignatureHeaderValidator;
    }
}
