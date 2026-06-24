package org.dromara.ai.provider;

import java.util.Map;

public record TtsGenerateRequest(
    String text,
    String voice,
    String format,
    Integer sampleRate,
    String businessType,
    Map<String, Object> metadata
) {
}
