package org.dromara.ai.provider;

import java.util.Map;

public record StreamingAsrRequest(
    String format,
    Integer sampleRate,
    String language,
    Map<String, Object> metadata
) {
}

