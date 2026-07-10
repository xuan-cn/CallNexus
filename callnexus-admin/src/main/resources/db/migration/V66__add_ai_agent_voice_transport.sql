-- AI 助手实时语音传输模式：HTTP（默认，兼容现有分段模式）或 WS（流式）。
-- 插件通过通道变量 callnexus_ai_voice_transport 感知本次通话该走哪条通路。
ALTER TABLE cc_ai_agent
    ADD COLUMN voice_transport VARCHAR(16) NOT NULL DEFAULT 'HTTP' COMMENT 'AI 实时语音传输模式：HTTP 或 WS' AFTER welcome_message,
    ADD COLUMN voice_transport_ws_url VARCHAR(256) NULL COMMENT 'WS 模式下 UniMRCP 插件连接的地址，留空表示使用系统默认' AFTER voice_transport;
