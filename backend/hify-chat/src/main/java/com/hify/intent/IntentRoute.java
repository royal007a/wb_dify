package com.hify.intent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum IntentRoute {
    UNKNOWN("unknown"),
    CLARIFY("clarify"),
    TOOL("tool"),
    WORKFLOW("workflow");

    private final String wireValue;

    IntentRoute(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static IntentRoute fromWireValue(String value) {
        return Arrays.stream(values())
                .filter(route -> route.wireValue.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported intent route: " + value));
    }
}
