package org.dromara.ai.realtime;

/**
 * Emitted after the UniMRCP synth plugin confirms that the first PCM block for
 * a speech segment has reached its local playback buffer.
 */
public record AiRealtimeTtsAudioReadyEvent(
    String tenantId,
    String callId,
    String turnId,
    Integer sequence
) {
}
