-- 为顶部全局 AI 助手指定唯一的系统内部助手。
ALTER TABLE cc_ai_agent
    ADD COLUMN system_assistant TINYINT NOT NULL DEFAULT 0 COMMENT '是否为系统内部助手' AFTER history_message_limit,
    ADD COLUMN system_assistant_guard VARCHAR(20)
        GENERATED ALWAYS AS (CASE WHEN system_assistant = 1 AND deleted = 0 THEN tenant_id ELSE NULL END) STORED
        COMMENT '系统内部助手租户唯一约束辅助列' AFTER system_assistant,
    ADD UNIQUE KEY uk_cc_ai_agent_system_assistant (system_assistant_guard);
