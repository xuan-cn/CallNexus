-- 增加独立的实时 TTS 能力配置，用于阿里云百炼实时 ASR/TTS 和后续 UniMRCP Speech Bridge。
ALTER TABLE cc_ai_speech_provider
    ADD COLUMN streaming_tts_enabled TINYINT NOT NULL DEFAULT 0 COMMENT '是否启用实时流式语音合成能力' AFTER tts_enabled,
    ADD COLUMN default_streaming_tts TINYINT NOT NULL DEFAULT 0 COMMENT '是否为默认实时语音合成服务商' AFTER default_tts,
    ADD COLUMN streaming_tts_endpoint_url VARCHAR(500) NULL COMMENT '实时流式语音合成服务地址' AFTER timeout_seconds,
    ADD COLUMN streaming_tts_options_json TEXT NULL COMMENT '实时语音合成厂商扩展参数JSON' AFTER streaming_tts_endpoint_url,
    ADD COLUMN default_streaming_tts_guard VARCHAR(20)
        GENERATED ALWAYS AS (CASE WHEN default_streaming_tts = 1 AND enabled = 1 AND deleted = 0 THEN tenant_id ELSE NULL END) STORED
        COMMENT '租户默认实时语音合成唯一约束辅助列',
    ADD UNIQUE KEY uk_cc_ai_speech_provider_default_streaming_tts (default_streaming_tts_guard);

-- 已启用的百炼 TTS 配置同步具备实时 TTS 配置入口，是否设为默认由管理员明确选择。
UPDATE cc_ai_speech_provider
SET streaming_tts_enabled = 1
WHERE provider_type = 'ALIYUN_DASHSCOPE'
  AND enabled = 1
  AND deleted = 0
  AND tts_enabled = 1;
