package org.dromara.ai.domain.response;

import org.dromara.ai.speech.definition.ModelDefinition;
import org.dromara.ai.speech.definition.VoiceDefinition;

import java.util.List;

public record SpeechCapabilityCatalogResponse(
    List<ModelDefinition> models,
    List<VoiceDefinition> voices
) {
    public SpeechCapabilityCatalogResponse {
        models = models == null ? List.of() : List.copyOf(models);
        voices = voices == null ? List.of() : List.copyOf(voices);
    }
}
