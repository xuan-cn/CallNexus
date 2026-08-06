ALTER TABLE cc_ai_agent
    ADD COLUMN barge_in_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否允许用户打断AI播报' AFTER voice_transport_ws_url,
    ADD COLUMN opening_barge_in_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否允许打断开场白' AFTER barge_in_enabled,
    ADD COLUMN barge_in_mode VARCHAR(16) NOT NULL DEFAULT 'STANDARD' COMMENT '打断环境模式：SENSITIVE、STANDARD、NOISY' AFTER opening_barge_in_enabled,
    ADD COLUMN barge_in_grace_ms INT NOT NULL DEFAULT 500 COMMENT '每段播报开始后的打断保护毫秒数' AFTER barge_in_mode;
