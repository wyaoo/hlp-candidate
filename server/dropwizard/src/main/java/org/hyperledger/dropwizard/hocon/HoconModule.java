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
package org.hyperledger.dropwizard.hocon;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValue;

public class HoconModule extends SimpleModule {

    public HoconModule() {
        super("Hocon");

        this.addDeserializer(ConfigValue.class, HoconDeserializer.CONFIG_VALUE);
        this.addDeserializer(ConfigObject.class, HoconDeserializer.CONFIG_OBJECT);
        this.addDeserializer(ConfigList.class, HoconDeserializer.CONFIG_LIST);
        this.addDeserializer(Config.class, HoconDeserializer.CONFIG);
    }
}
