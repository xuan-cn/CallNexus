package org.dromara.ai.speech.definition;

import java.util.List;

public record FieldDefinition(
    String key,
    String label,
    SpeechFieldType type,
    boolean required,
    boolean secret,
    String placeholder,
    Object defaultValue,
    List<OptionDefinition> options,
    boolean advanced
) {
    public FieldDefinition {
        options = options == null ? List.of() : List.copyOf(options);
    }
}
