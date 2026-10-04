package com.hify.common;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Explicit admission guard. Never normalize input or include rejected values in errors. */
public final class TextInput {
    private TextInput() {}

    public static void requireNoNul(String... values) {
        for (String value : values) {
            if (value != null && value.indexOf(0) >= 0)
                throw new BizException(ErrorCode.PARAM_ERROR, "文本不能包含 NUL 字符");
        }
    }

    public static void requireNoNulInJson(JsonNode root) {
        if (root == null) return;
        var pending = new ArrayDeque<JsonNode>();
        Set<JsonNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(root);
        while (!pending.isEmpty()) {
            JsonNode node = pending.removeLast();
            if (!seen.add(node)) continue;
            if (node.isTextual()) requireNoNul(node.textValue());
            if (node.isObject()) node.fields().forEachRemaining(field -> {
                requireNoNul(field.getKey());
                pending.add(field.getValue());
            });
            else if (node.isArray()) node.forEach(pending::add);
        }
    }
}
