-- 将原 TTS 服务商升级为统一语音服务商，保留原主键以兼容历史任务和转写记录。
RENAME TABLE cc_ai_tts_provider TO cc_ai_speech_provider;

ALTER TABLE cc_ai_speech_provider
    ADD COLUMN tts_enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用语音合成能力' AFTER provider_type,
    ADD COLUMN recording_asr_enabled TINYINT NOT NULL DEFAULT 0 COMMENT '是否启用录音文件识别能力' AFTER tts_enabled,
    ADD COLUMN streaming_asr_enabled TINYINT NOT NULL DEFAULT 0 COMMENT '是否启用实时流式识别能力' AFTER recording_asr_enabled,
    ADD COLUMN default_tts TINYINT NOT NULL DEFAULT 0 COMMENT '是否为默认语音合成服务商' AFTER streaming_asr_enabled,
    ADD COLUMN default_recording_asr TINYINT NOT NULL DEFAULT 0 COMMENT '是否为默认录音识别服务商' AFTER default_tts,
    ADD COLUMN default_streaming_asr TINYINT NOT NULL DEFAULT 0 COMMENT '是否为默认流式识别服务商' AFTER default_recording_asr,
    ADD COLUMN recording_asr_endpoint_url VARCHAR(500) NULL COMMENT '录音文件识别服务地址' AFTER timeout_seconds,
    ADD COLUMN streaming_asr_endpoint_url VARCHAR(500) NULL COMMENT '实时流式识别服务地址' AFTER recording_asr_endpoint_url,
    ADD COLUMN asr_language VARCHAR(32) NOT NULL DEFAULT 'zh-CN' COMMENT '语音识别语言' AFTER streaming_asr_endpoint_url,
    ADD COLUMN asr_format VARCHAR(16) NOT NULL DEFAULT 'wav' COMMENT '语音识别默认音频格式' AFTER asr_language,
    ADD COLUMN asr_sample_rate INT NOT NULL DEFAULT 8000 COMMENT '语音识别默认采样率' AFTER asr_format,
    ADD COLUMN asr_enable_punctuation TINYINT NOT NULL DEFAULT 1 COMMENT '语音识别是否启用标点' AFTER asr_sample_rate,
    ADD COLUMN asr_enable_itn TINYINT NOT NULL DEFAULT 1 COMMENT '语音识别是否启用数字格式化' AFTER asr_enable_punctuation,
    ADD COLUMN asr_enable_intermediate_result TINYINT NOT NULL DEFAULT 0 COMMENT '流式识别是否返回中间结果' AFTER asr_enable_itn,
    ADD COLUMN asr_silence_timeout_ms INT NOT NULL DEFAULT 800 COMMENT '语音识别静音断句毫秒数' AFTER asr_enable_intermediate_result,
    ADD COLUMN asr_max_sentence_ms INT NOT NULL DEFAULT 60000 COMMENT '语音识别最大单句时长毫秒数' AFTER asr_silence_timeout_ms,
    ADD COLUMN asr_options_json TEXT NULL COMMENT '语音识别厂商扩展参数JSON' AFTER asr_max_sentence_ms,
    ADD COLUMN default_tts_guard VARCHAR(20)
        GENERATED ALWAYS AS (CASE WHEN default_tts = 1 AND enabled = 1 AND deleted = 0 THEN tenant_id ELSE NULL END) STORED
        COMMENT '租户默认语音合成唯一约束辅助列',
    ADD COLUMN default_recording_asr_guard VARCHAR(20)
        GENERATED ALWAYS AS (CASE WHEN default_recording_asr = 1 AND enabled = 1 AND deleted = 0 THEN tenant_id ELSE NULL END) STORED
        COMMENT '租户默认录音识别唯一约束辅助列',
    ADD COLUMN default_streaming_asr_guard VARCHAR(20)
        GENERATED ALWAYS AS (CASE WHEN default_streaming_asr = 1 AND enabled = 1 AND deleted = 0 THEN tenant_id ELSE NULL END) STORED
        COMMENT '租户默认流式识别唯一约束辅助列',
    ADD UNIQUE KEY uk_cc_ai_speech_provider_default_tts (default_tts_guard),
    ADD UNIQUE KEY uk_cc_ai_speech_provider_default_recording_asr (default_recording_asr_guard),
    ADD UNIQUE KEY uk_cc_ai_speech_provider_default_streaming_asr (default_streaming_asr_guard);

-- 阿里云 NLS 同一份凭证同时具备 TTS、录音 ASR 和流式 ASR 配置能力。
UPDATE cc_ai_speech_provider
SET recording_asr_enabled = CASE WHEN provider_type = 'ALIYUN_NLS' THEN 1 ELSE 0 END,
    streaming_asr_enabled = CASE WHEN provider_type = 'ALIYUN_NLS' THEN 1 ELSE 0 END,
    recording_asr_endpoint_url = CASE WHEN provider_type = 'ALIYUN_NLS' THEN endpoint_url ELSE NULL END,
    streaming_asr_endpoint_url = CASE WHEN provider_type = 'ALIYUN_NLS' THEN endpoint_url ELSE NULL END;

-- 每个租户选择第一条启用记录作为默认 TTS。
UPDATE cc_ai_speech_provider provider
JOIN (
    SELECT tenant_id, MIN(id) AS provider_id
    FROM cc_ai_speech_provider
    WHERE enabled = 1 AND deleted = 0 AND tts_enabled = 1
    GROUP BY tenant_id
) selected ON selected.provider_id = provider.id
SET provider.default_tts = 1;

-- 每个租户选择第一条启用的阿里云 NLS 作为默认录音和流式 ASR。
UPDATE cc_ai_speech_provider provider
JOIN (
    SELECT tenant_id, MIN(id) AS provider_id
    FROM cc_ai_speech_provider
    WHERE enabled = 1 AND deleted = 0 AND provider_type = 'ALIYUN_NLS'
    GROUP BY tenant_id
) selected ON selected.provider_id = provider.id
SET provider.default_recording_asr = 1,
    provider.default_streaming_asr = 1;

