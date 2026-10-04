package com.hify.workflow.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;

/** Scoped readers: never change the application's shared ObjectMapper settings. */
public final class WorkflowJson {
    private WorkflowJson() {}

    public static JsonNode readTree(ObjectMapper json, String raw) throws IOException {
        return json.readerFor(JsonNode.class).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(raw);
    }

    public static final class DecimalTreeDeserializer extends JsonDeserializer<JsonNode> {
        @Override public JsonNode getAbsentValue(DeserializationContext context) { return null; }
        @Override public JsonNode getNullValue(DeserializationContext context) {
            return com.fasterxml.jackson.databind.node.NullNode.instance;
        }
        @Override public JsonNode deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return ((ObjectMapper) parser.getCodec()).readerFor(JsonNode.class)
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(parser);
        }
    }
}
