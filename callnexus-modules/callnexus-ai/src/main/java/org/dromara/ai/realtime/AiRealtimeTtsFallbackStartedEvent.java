package org.dromara.ai.realtime;

/**
 * Emitted when realtime TTS has produced no playable audio and the internal
 * WebSocket endpoint starts synthesizing the same segment through HTTP.
 */
public record AiRealtimeTtsFallbackStartedEvent(
    String tenantId,
    String callId,
    String turnId,
    Integer sequence,
    String reason
) {
}
