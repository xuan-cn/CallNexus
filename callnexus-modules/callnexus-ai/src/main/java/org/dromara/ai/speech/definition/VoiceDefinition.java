package org.dromara.ai.speech.definition;

public record VoiceDefinition(
    String id,
    String label,
    boolean recommended
) {
}
