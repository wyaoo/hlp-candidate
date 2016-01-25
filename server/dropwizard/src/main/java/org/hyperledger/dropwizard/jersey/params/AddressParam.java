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
package org.hyperledger.dropwizard.jersey.params;

import io.dropwizard.jersey.params.AbstractParam;
import org.hyperledger.account.UIAddress;

/**
 *
 */
public class AddressParam extends AbstractParam<UIAddress> {

    /**
     * Given an input value from a client, creates a parameter wrapping its parsed value.
     *
     * @param input an input value from a client request
     */
    public AddressParam(String input) {
        super(input);
    }

    @Override
    protected UIAddress parse(String input) throws Exception {
        return UIAddress.fromSatoshiStyle(input);
    }
}
