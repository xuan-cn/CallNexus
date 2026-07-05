-- AI 助手本地对话开场白。
ALTER TABLE cc_ai_agent
    ADD COLUMN welcome_message VARCHAR(2000) NULL COMMENT 'AI助手开场白，创建新对话时作为首条AI消息' AFTER system_prompt;
