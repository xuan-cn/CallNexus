package org.dromara.ai.domain.response;

/**
 * AI 实时 ASR 桥接接口响应，对应 /api/internal/ai/realtime/asr 的 data 部分。
 */
public record AiRealtimeAsrResponse(String text, Double confidence, String language) {
}