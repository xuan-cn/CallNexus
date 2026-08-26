package org.dromara.ai.speech.definition;

import java.util.List;

public record CapabilityDefinition(
    SpeechCapability capability,
    String label,
    boolean supported,
    String defaultModel,
    List<ModelDefinition> models,
    boolean supportsVoiceList,
    boolean supportsVoicePreview,
    List<FieldDefinition> fields
) {
    public CapabilityDefinition {
        models = models == null ? List.of() : List.copyOf(models);
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
