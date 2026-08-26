package org.dromara.ai.speech.definition;

import java.util.List;
import java.util.Map;

public interface SpeechProviderDefinition {
    String providerType();

    String label();

    String description();

    List<FieldDefinition> credentialFields();

    Map<SpeechCapability, CapabilityDefinition> capabilities();

    String resolveEndpoint(SpeechCapability capability, Map<String, Object> credentials);
}
