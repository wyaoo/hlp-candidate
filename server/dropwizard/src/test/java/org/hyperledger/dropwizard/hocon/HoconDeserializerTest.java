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

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueType;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

public class HoconDeserializerTest {

    @Test
    public void testSimpleArray() throws Exception {
        final String JSON = "[1,true,\"foo\"]";
        ConfigList value = MAPPER.readValue(JSON, ConfigList.class);
        assertEquals(ConfigValueType.LIST, value.valueType());
        assertEquals(3, value.size());
        assertEquals(ConfigValueType.NUMBER, value.get(0).valueType());
        assertEquals(true, value.get(1).unwrapped());
        assertEquals(ConfigValueType.STRING, value.get(2).valueType());
    }

    @Test
    public void testNestedArray() throws Exception {
        final String JSON = "[1,[false,45],{\"foo\":13}]";
        ConfigList value = MAPPER.readValue(JSON, ConfigList.class);
        assertEquals(ConfigValueType.LIST, value.valueType());
        assertEquals(3, value.size());
        assertEquals(ConfigValueType.NUMBER, value.get(0).valueType());
        assertEquals(ConfigValueType.LIST, value.get(1).valueType());
        assertEquals(ConfigValueType.OBJECT, value.get(2).valueType());
    }

    @Test
    public void testSimpleObject() throws Exception {
        final String JSON = "{\"a\":12.5,\"b\":\"Text\"}";
        ConfigObject value = MAPPER.readValue(JSON, ConfigObject.class);
        assertEquals(ConfigValueType.OBJECT, value.valueType());
        assertEquals(2, value.size());

        assertEquals(ConfigValueType.NUMBER, value.get("a").valueType());
        assertEquals(12.5, value.get("a").unwrapped());
        assertEquals(ConfigValueType.STRING, value.get("b").valueType());
        assertEquals("Text", value.get("b").unwrapped());
    }

    @Test
    public void testNestedObject() throws Exception {
        final String JSON = "{\"array\":[1,2],\"obj\":{\"first\":true}}";
        ConfigObject value = MAPPER.readValue(JSON, ConfigObject.class);
        assertEquals(ConfigValueType.OBJECT, value.valueType());
        assertEquals(2, value.size());

        ConfigList array = (ConfigList) value.get("array");
        assertEquals(ConfigValueType.LIST, array.valueType());
        assertEquals(2, (array.unwrapped().size()));

        ConfigValue objValue = value.get("obj");
        assertEquals(ConfigValueType.OBJECT, objValue.valueType());
        ConfigObject obj = (ConfigObject) objValue;
        assertEquals(1, (obj.size()));
    }

    protected final static ObjectMapper MAPPER = new ObjectMapper().registerModule(new HoconModule());


    protected String serializeAsString(JsonValue node) throws IOException {
        return MAPPER.writeValueAsString(node);
    }
}
