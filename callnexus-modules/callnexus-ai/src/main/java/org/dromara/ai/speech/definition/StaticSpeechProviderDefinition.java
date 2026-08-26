package org.dromara.ai.speech.definition;

import java.util.List;
import java.util.Map;

public record StaticSpeechProviderDefinition(
    String providerType,
    String label,
    String description,
    List<FieldDefinition> credentialFields,
    Map<SpeechCapability, CapabilityDefinition> capabilities,
    Map<SpeechCapability, String> endpoints
) implements SpeechProviderDefinition {

    public StaticSpeechProviderDefinition {
        credentialFields = credentialFields == null ? List.of() : List.copyOf(credentialFields);
        capabilities = capabilities == null ? Map.of() : Map.copyOf(capabilities);
        endpoints = endpoints == null ? Map.of() : Map.copyOf(endpoints);
    }

    @Override
    public String resolveEndpoint(SpeechCapability capability, Map<String, Object> credentials) {
        String endpoint = endpoints.get(capability);
        if (endpoint == null) {
            return null;
        }
        String resolved = endpoint;
        for (Map.Entry<String, Object> entry : credentials.entrySet()) {
            resolved = resolved.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return resolved.contains("{") ? null : resolved;
    }
}
