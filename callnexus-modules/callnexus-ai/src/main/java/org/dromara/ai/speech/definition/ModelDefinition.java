package org.dromara.ai.speech.definition;

import java.util.List;
import java.util.Map;

public record ModelDefinition(
    String id,
    String label,
    boolean recommended,
    List<String> formats,
    List<Integer> sampleRates,
    List<VoiceDefinition> voices,
    Map<String, Object> parameterSchema
) {
    public ModelDefinition {
        formats = formats == null ? List.of() : List.copyOf(formats);
        sampleRates = sampleRates == null ? List.of() : List.copyOf(sampleRates);
        voices = voices == null ? List.of() : List.copyOf(voices);
        parameterSchema = parameterSchema == null ? Map.of() : Map.copyOf(parameterSchema);
    }

    public ModelDefinition(String id, String label, boolean recommended) {
        this(id, label, recommended, List.of(), List.of(), List.of(), Map.of());
    }

    public ModelDefinition(String id, String label, boolean recommended,
                           List<String> formats, List<Integer> sampleRates,
                           List<VoiceDefinition> voices) {
        this(id, label, recommended, formats, sampleRates, voices, Map.of());
    }
}
