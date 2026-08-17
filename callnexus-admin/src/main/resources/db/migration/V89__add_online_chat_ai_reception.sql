-- 在线客服接入 AI 助手。
ALTER TABLE cc_chat_channel
    ADD COLUMN ai_enabled TINYINT NOT NULL DEFAULT 0 COMMENT '是否启用AI接待' AFTER skill_group_id,
    ADD COLUMN ai_agent_id BIGINT NULL COMMENT 'AI助手ID' AFTER ai_enabled,
    ADD KEY idx_cc_chat_channel_ai_agent (tenant_id, ai_enabled, ai_agent_id);

ALTER TABLE cc_chat_conversation
    ADD COLUMN skill_group_id BIGINT NULL COMMENT '在线客服技能组ID' AFTER channel_id,
    ADD COLUMN ai_agent_id BIGINT NULL COMMENT 'AI助手ID' AFTER visitor_id,
    ADD COLUMN ai_conversation_id BIGINT NULL COMMENT 'AI内部会话ID' AFTER ai_agent_id,
    ADD KEY idx_cc_chat_conversation_skill_group (tenant_id, skill_group_id, status, queued_at),
    ADD KEY idx_cc_chat_conversation_ai (tenant_id, ai_agent_id, ai_conversation_id);

UPDATE cc_chat_conversation conversation
JOIN cc_chat_channel channel ON channel.id = conversation.channel_id
SET conversation.skill_group_id = channel.skill_group_id
WHERE conversation.skill_group_id IS NULL;
