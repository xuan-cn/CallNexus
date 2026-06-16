ALTER TABLE cc_outbound_task
    ADD COLUMN auto_assign_due_retry TINYINT NOT NULL DEFAULT 0 COMMENT '是否自动分配到期重呼名单' AFTER retry_result_codes,
    ADD COLUMN retry_assignee_agent_id BIGINT NULL COMMENT '到期重呼指定坐席ID' AFTER auto_assign_due_retry;

