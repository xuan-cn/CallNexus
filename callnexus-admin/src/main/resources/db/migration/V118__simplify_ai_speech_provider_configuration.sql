-- AI 语音服务商改为结构化配置。旧扩展 JSON 不再作为模型和音色的配置来源。
ALTER TABLE cc_ai_speech_provider
    ADD COLUMN tts_model VARCHAR(128) NULL COMMENT '普通TTS模型' AFTER default_streaming_asr,
    ADD COLUMN streaming_tts_model VARCHAR(128) NULL COMMENT '流式TTS模型' AFTER tts_model,
    ADD COLUMN recording_asr_model VARCHAR(128) NULL COMMENT '录音ASR模型' AFTER streaming_tts_model,
    ADD COLUMN streaming_asr_model VARCHAR(128) NULL COMMENT '流式ASR模型' AFTER recording_asr_model,
    ADD COLUMN tts_voice VARCHAR(128) NULL COMMENT '普通TTS音色' AFTER streaming_asr_model,
    ADD COLUMN streaming_tts_voice VARCHAR(128) NULL COMMENT '流式TTS音色' AFTER tts_voice,
    ADD COLUMN tts_endpoint_mode VARCHAR(16) NOT NULL DEFAULT 'AUTO' COMMENT '普通TTS地址模式：AUTO/CUSTOM' AFTER streaming_tts_voice,
    ADD COLUMN streaming_tts_endpoint_mode VARCHAR(16) NOT NULL DEFAULT 'AUTO' COMMENT '流式TTS地址模式：AUTO/CUSTOM' AFTER tts_endpoint_mode,
    ADD COLUMN recording_asr_endpoint_mode VARCHAR(16) NOT NULL DEFAULT 'AUTO' COMMENT '录音ASR地址模式：AUTO/CUSTOM' AFTER streaming_tts_endpoint_mode,
    ADD COLUMN streaming_asr_endpoint_mode VARCHAR(16) NOT NULL DEFAULT 'AUTO' COMMENT '流式ASR地址模式：AUTO/CUSTOM' AFTER recording_asr_endpoint_mode,
    ADD COLUMN credential_json TEXT NULL COMMENT '服务商非主密钥凭证及连接参数JSON' AFTER auth_token,
    ADD COLUMN configuration_schema_version INT NOT NULL DEFAULT 2 COMMENT '语音配置结构版本' AFTER credential_json,
    ADD COLUMN last_test_status VARCHAR(32) NULL COMMENT '最近测试状态' AFTER enabled,
    ADD COLUMN last_test_message VARCHAR(500) NULL COMMENT '最近测试结果摘要' AFTER last_test_status,
    ADD COLUMN last_test_time DATETIME NULL COMMENT '最近测试时间' AFTER last_test_message;

-- 不转换旧 JSON，也不推断旧地址模式。已有服务商需要在新页面重新保存。
