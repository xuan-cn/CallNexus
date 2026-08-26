package org.dromara.ai.domain.response;

import org.dromara.ai.speech.definition.SpeechCapability;

import java.time.LocalDateTime;
import java.util.Map;

public record SpeechProviderCatalogResponse(
    Long providerId,
    String providerType,
    String catalogVersion,
    String source,
    LocalDateTime refreshedAt,
    Map<SpeechCapability, SpeechCapabilityCatalogResponse> capabilities,
    String message
) {
    public SpeechProviderCatalogResponse {
        capabilities = capabilities == null ? Map.of() : Map.copyOf(capabilities);
    }
}
