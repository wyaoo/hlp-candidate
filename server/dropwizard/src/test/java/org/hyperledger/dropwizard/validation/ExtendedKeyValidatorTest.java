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
import org.junit.BeforeClass;
import org.junit.Test;

import javax.validation.*;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;


/**
 *
 */
public class ExtendedKeyValidatorTest {
    @BeforeClass
    public static void setUp() throws Exception {
        ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    private static final class TestBean {
        @Valid
        @ValidExtendedKey
        MasterKey anyKey;

        @Valid
        @ValidExtendedKey(keyType = ValidExtendedKey.KeyType.PUBLIC)
        MasterKey pubKey;

        @Valid
        @ValidExtendedKey(keyType = ValidExtendedKey.KeyType.PRIVATE)
        MasterKey privKey;
    }

    private static Validator validator;

    @Test
    public void testValidCase() {
        TestBean b = new TestBean();
        b.anyKey = MasterPrivateKey.createNew();
        b.privKey = MasterPrivateKey.createNew();
        b.pubKey = MasterPrivateKey.createNew().getMasterPublic();

        assertValid(b);

        b = new TestBean();
        b.anyKey = MasterPrivateKey.createNew().getMasterPublic();
        b.privKey = MasterPrivateKey.createNew();
        b.pubKey = MasterPrivateKey.createNew().getMasterPublic();

        assertValid(b);
    }

    @Test
    public void testInvalidPrivate() {
        TestBean b = new TestBean();
        b.anyKey = MasterPrivateKey.createNew().getMasterPublic();
        b.privKey = MasterPrivateKey.createNew().getMasterPublic();
        b.pubKey = MasterPrivateKey.createNew();

        Set<ConstraintViolation<TestBean>> violations = validator.validate(b);
        assertThat(violations).hasSize(2)
                .extracting("message")
                .contains("Extended key must be PRIVATE",
                        "Extended key must be PUBLIC");
    }

    private void assertValid(TestBean bean) {
        Set<ConstraintViolation<TestBean>> violations = validator.validate(bean);
        assertEquals(0, violations.size());
    }
}
