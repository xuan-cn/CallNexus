package org.dromara.ai.speech.definition;

public enum EndpointMode {
    AUTO,
    CUSTOM;

    public static EndpointMode from(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        return valueOf(value.trim().toUpperCase());
    }
}
