package org.dromara.ai.service;

import java.util.Map;

public interface AiRealtimeTelephonyGateway {

    /**
     * 提交一段 TTS 播报。
     *
     * <p>turnId/seq/turnEnd 用于 WS 传输模式下让 UniMRCP 插件按 (callId,turnId) 复用同一条
     * TTS WebSocket：同一轮的多段共用一条连接，seq 保证顺序，turnEnd=true 时插件发 flush。
     * HTTP 传输模式下这些参数会被下发为通道变量但不影响行为。</p>
     *
     * @param turnId  本轮回复标识（同一轮内多段一致）
     * @param seq     本轮内的段序号（从 1 递增）
     * @param turnEnd 是否为本轮最后一段
     */
    void speak(Long nodeId, String customerLegUuid, String text, String voice,
               String turnId, int seq, boolean turnEnd);

    void recognize(Long nodeId, String customerLegUuid);

    boolean callExists(Long nodeId, String customerLegUuid);

    Map<String, String> getChannelVariables(Long nodeId, String customerLegUuid, String... names);

    /**
     * 在通话通道上下发语音传输模式与可选的 WS 端点，供 UniMRCP 插件下一次 speak/recognize 感知。
     *
     * @param transport {@link VoiceTransport#HTTP} 或 {@link VoiceTransport#WS}
     * @param wsUrl     WS 模式下的连接地址，允许为空（表示使用插件侧默认配置）
     */
    void applyVoiceTransport(Long nodeId, String customerLegUuid, VoiceTransport transport, String wsUrl);
}
