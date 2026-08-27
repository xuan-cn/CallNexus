-- 统一语音服务商允许只启用某一种能力。
-- 仅启用录音/流式 ASR 的服务商不会填写旧的普通 TTS endpoint_url。
ALTER TABLE cc_ai_speech_provider
    MODIFY COLUMN endpoint_url VARCHAR(512) NULL COMMENT '普通TTS请求地址；未启用普通TTS时允许为空';
