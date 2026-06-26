package org.dromara.ai.provider;

import java.util.Map;

public record AsrTranscribeRequest(
    byte[] audioBytes,
    String format,
    Integer sampleRate,
    String businessType,
    Map<String, Object> metadata
) {
}
