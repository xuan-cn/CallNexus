package org.dromara.ai.realtime;

/**
 * Emitted after all PCM bytes for one TTS segment have been sent to the
 * UniMRCP synth plugin. This does not mean that RTP playback has completed.
 */
public record AiRealtimeTtsSegmentSentEvent(
    String tenantId,
    String callId,
    String turnId,
    Integer sequence,
    long audioBytes,
    int sampleRate
) {
}
