package org.dromara.ai.provider;

public record TtsGenerateResult(
    byte[] audioBytes,
    String contentType,
    String fileSuffix,
    Long durationMs
) {
}
