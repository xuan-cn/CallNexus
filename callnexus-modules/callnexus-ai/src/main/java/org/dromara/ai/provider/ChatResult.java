package org.dromara.ai.provider;

public record ChatResult(String content, Integer inputTokens, Integer outputTokens) {}
