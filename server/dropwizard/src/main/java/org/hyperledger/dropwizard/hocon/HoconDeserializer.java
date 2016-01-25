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
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.typesafe.config.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public abstract class HoconDeserializer<T> extends StdDeserializer<T> {

    public HoconDeserializer(Class<T> vc) {
        super(vc);
    }

    @SuppressWarnings("unchecked")
    public static JsonDeserializer<ConfigValue> CONFIG_VALUE = new HoconDeserializer(ConfigValue.class) {
        public ConfigValue deserialize(JsonParser jsonParser, DeserializationContext context) throws IOException {
            switch (jsonParser.getCurrentToken()) {
                case START_OBJECT:
                    return deserializeObject(jsonParser, context);
                case START_ARRAY:
                    return deserializeArray(jsonParser, context);
                default:
                    return deserializeScalar(jsonParser, context);
            }
        }
    };

    @SuppressWarnings("unchecked")
    public static JsonDeserializer<ConfigList> CONFIG_LIST = new HoconDeserializer(ConfigList.class) {
        public ConfigList deserialize(JsonParser jsonParser, DeserializationContext context) throws IOException {
            switch (jsonParser.getCurrentToken()) {
                case START_ARRAY:
                    return deserializeArray(jsonParser, context);
                default:
                    throw context.mappingException(ConfigList.class);
            }
        }
    };

    @SuppressWarnings("unchecked")
    public static JsonDeserializer<ConfigObject> CONFIG_OBJECT = new HoconDeserializer(ConfigObject.class) {
        public ConfigObject deserialize(JsonParser jsonParser, DeserializationContext context) throws IOException {
            switch (jsonParser.getCurrentToken()) {
                case START_OBJECT:
                    return deserializeObject(jsonParser, context);
                default:
                    throw context.mappingException(ConfigObject.class);
            }
        }
    };

    @SuppressWarnings("unchecked")
    public static JsonDeserializer<Config> CONFIG = new HoconDeserializer(Config.class) {
        public Config deserialize(JsonParser jsonParser, DeserializationContext context) throws IOException {
            switch (jsonParser.getCurrentToken()) {
                case START_OBJECT:
                    return deserializeObject(jsonParser, context).toConfig();
                default:
                    throw context.mappingException(Config.class);
            }
        }
    };

    protected ConfigObject deserializeObject(JsonParser jp, DeserializationContext ctxt) throws IOException {
        HashMap<String, Object> mapping = new HashMap<>();

        while (jp.nextToken() != JsonToken.END_OBJECT) {
            String name = jp.getCurrentName();
            JsonToken t = jp.nextToken();
            switch (t) {
                case START_ARRAY:
                    mapping.put(name, deserializeArray(jp, ctxt).unwrapped());
                    break;
                case START_OBJECT:
                    mapping.put(name, deserializeObject(jp, ctxt).unwrapped());
                    break;
                case VALUE_FALSE:
                    mapping.put(name, false);
                    break;
                case VALUE_TRUE:
                    mapping.put(name, true);
                    break;
                case VALUE_NULL:
                    mapping.put(name, null);
                    break;
                case VALUE_NUMBER_FLOAT:
                    if (jp.getNumberType() == JsonParser.NumberType.BIG_DECIMAL) {
                        mapping.put(name, jp.getDecimalValue());
                    } else {
                        mapping.put(name, jp.getDoubleValue());
                    }
                    break;
                case VALUE_NUMBER_INT:
                    // very cumbersome... but has to be done
                    switch (jp.getNumberType()) {
                        case LONG:
                            mapping.put(name, jp.getLongValue());
                            break;
                        case INT:
                            mapping.put(name, jp.getIntValue());
                            break;
                        default:
                            mapping.put(name, jp.getBigIntegerValue());
                    }
                    break;
                case VALUE_STRING:
                    mapping.put(name, jp.getText());
                    break;
                case VALUE_EMBEDDED_OBJECT: {
                    Object ob = jp.getEmbeddedObject();
                    if (ob instanceof byte[]) {
                        String b64 = ctxt.getBase64Variant().encode((byte[]) ob, false);
                        mapping.put(name, b64);
                        break;
                    }
                }
                default:
                    throw ctxt.mappingException(_valueClass);
            }
        }
        return ConfigValueFactory.fromMap(mapping);
    }

    protected ConfigList deserializeArray(JsonParser jp, DeserializationContext ctxt) throws IOException {

        List<Object> values = new ArrayList<>();

        JsonToken t;
        while ((t = jp.nextToken()) != JsonToken.END_ARRAY) {
            switch (t) {
                case START_ARRAY:
                    values.add(deserializeArray(jp, ctxt).unwrapped());
                    break;
                case START_OBJECT:
                    values.add(deserializeObject(jp, ctxt).unwrapped());
                    break;
                case VALUE_FALSE:
                    values.add(false);
                    break;
                case VALUE_TRUE:
                    values.add(true);
                    break;
                case VALUE_NULL:
                    values.add(null);
                    break;
                case VALUE_NUMBER_FLOAT:
                    if (jp.getNumberType() == JsonParser.NumberType.BIG_DECIMAL) {
                        values.add(jp.getDecimalValue());
                    } else {
                        values.add(jp.getDoubleValue());
                    }
                    break;
                case VALUE_NUMBER_INT:
                    // very cumbersome... but has to be done
                    switch (jp.getNumberType()) {
                        case LONG:
                            values.add(jp.getLongValue());
                            break;
                        case INT:
                            values.add(jp.getIntValue());
                            break;
                        default:
                            values.add(jp.getBigIntegerValue());
                    }
                    break;
                case VALUE_STRING:
                    values.add(jp.getText());
                    break;
                default:
                    throw ctxt.mappingException(_valueClass);
            }
        }
        return ConfigValueFactory.fromIterable(values);
    }

    protected ConfigValue deserializeScalar(JsonParser jp, DeserializationContext ctxt) throws IOException {
        switch (jp.getCurrentToken()) {
            case VALUE_EMBEDDED_OBJECT:
                throw ctxt.mappingException(JsonValue.class);
            case VALUE_FALSE:
                return ConfigValueFactory.fromAnyRef(false);
            case VALUE_TRUE:
                return ConfigValueFactory.fromAnyRef(true);
            case VALUE_NULL:
                return ConfigValueFactory.fromAnyRef(null);
            case VALUE_NUMBER_FLOAT:
                // very cumbersome... but has to be done
            {
                if (jp.getNumberType() == JsonParser.NumberType.BIG_DECIMAL) {
                    return ConfigValueFactory.fromAnyRef(jp.getDecimalValue());
                }
                return ConfigValueFactory.fromAnyRef(jp.getDoubleValue());
            }
            case VALUE_NUMBER_INT: {
                switch (jp.getNumberType()) {
                    case LONG:
                        return ConfigValueFactory.fromAnyRef(jp.getLongValue());
                    case INT:
                        return ConfigValueFactory.fromAnyRef(jp.getIntValue());
                    default:
                        return ConfigValueFactory.fromAnyRef(jp.getBigIntegerValue());
                }
            }
            case VALUE_STRING:
                return ConfigValueFactory.fromAnyRef(jp.getText());
            default: // errors, should never get here
//        case END_ARRAY:
//        case END_OBJECT:
//        case FIELD_NAME:
//        case NOT_AVAILABLE:
//        case START_ARRAY:
//        case START_OBJECT:
                throw ctxt.mappingException(_valueClass);
        }
    }

}
