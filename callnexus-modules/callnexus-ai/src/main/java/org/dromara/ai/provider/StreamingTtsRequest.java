package org.dromara.ai.provider;

import java.util.Map;

public record StreamingTtsRequest(
    String voice,
    String format,
    Integer sampleRate,
    Map<String, Object> metadata
) {
}
