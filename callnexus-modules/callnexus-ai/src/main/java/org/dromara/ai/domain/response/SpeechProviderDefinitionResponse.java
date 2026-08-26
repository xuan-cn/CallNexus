package org.dromara.ai.domain.response;

import org.dromara.ai.speech.definition.CapabilityDefinition;
import org.dromara.ai.speech.definition.FieldDefinition;
import org.dromara.ai.speech.definition.SpeechCapability;
import org.dromara.ai.speech.definition.SpeechProviderDefinition;

import java.util.List;
import java.util.Map;

public record SpeechProviderDefinitionResponse(
    String providerType,
    String label,
    String description,
    List<FieldDefinition> credentialFields,
    Map<SpeechCapability, CapabilityDefinition> capabilities
) {
    public static SpeechProviderDefinitionResponse from(SpeechProviderDefinition definition) {
        return new SpeechProviderDefinitionResponse(definition.providerType(), definition.label(),
            definition.description(), definition.credentialFields(), definition.capabilities());
    }
}
