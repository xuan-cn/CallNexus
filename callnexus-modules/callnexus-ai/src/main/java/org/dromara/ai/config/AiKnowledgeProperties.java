package org.dromara.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai")
public class AiKnowledgeProperties {
    private String knowledgeOssConfigKey = "knowledge-document";
    private String qdrantUrl = "http://127.0.0.1:6333";
    private String qdrantApiKey;
    private Integer indexWorkerConcurrency = 2;
    private Integer indexLeaseMinutes = 30;
    private Integer maxDocumentSizeMb = 50;
    private Integer maxChunkCountPerDocument = 20000;
    private Long chatStreamTimeoutSeconds = 120L;
    private Boolean realtimeEnabled = false;
    private String realtimeWebsocketUrl = "ws://127.0.0.1:8080/api/internal/ai/realtime/audio";
    private String realtimeTokenSecret;
    private Integer realtimeTokenTtlSeconds = 300;
    private Integer realtimeWorkerThreads = 8;
    private String realtimeTransport = "UNIMRCP";
    private UniMrcp unimrcp = new UniMrcp();

    @Data
    public static class UniMrcp {
        private String speakCommandTemplate = "api uuid_broadcast {uuid} speak::{profile}|{voice}|{text} both";
        private String recognizeCommandTemplate = "api luarun {detectScript} {uuid}";
        private String profile = "unimrcp";
        private String voice = "default";
        private String grammar = "{start-input-timers=true,no-input-timeout=15000}builtin:speech/transcribe transcribe";
        private String detectScript = "/usr/share/freeswitch/scripts/callnexus_detect_speech.lua";
        private Long speakCompleteDelayMs = 600L;
        private Long recognizeRetryDelayMs = 500L;
        private Long channelProbeIntervalMs = 2000L;
        private Integer maxConsecutiveEmptyRecognitions = 3;
        private Boolean hangupOnRecognitionIdle = true;
        private Long intentHangupDelayMs = 1000L;
        private String resultHeaderCandidates = "variable_detect_speech_result,variable_speech_result,Detect-Speech-Result,Speech-Result";
    }
}
