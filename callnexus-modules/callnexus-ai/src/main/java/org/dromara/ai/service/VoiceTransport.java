package org.dromara.ai.service;

/**
 * AI 实时通话的语音传输模式。
 * <ul>
 *     <li>{@link #HTTP}：UniMRCP 插件走 /api/internal/ai/realtime/tts (asr) HTTP 接口，逐段合成后播放。</li>
 *     <li>{@link #WS}：UniMRCP 插件走 /api/internal/ai/realtime/tts-stream (asr-stream) WebSocket，边生成边推送音频。</li>
 * </ul>
 */
public enum VoiceTransport {
    HTTP,
    WS;

    public static VoiceTransport fromNullable(String value) {
        if (value == null || value.isBlank()) {
            return HTTP;
        }
        try {
            return VoiceTransport.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return HTTP;
        }
    }
}
