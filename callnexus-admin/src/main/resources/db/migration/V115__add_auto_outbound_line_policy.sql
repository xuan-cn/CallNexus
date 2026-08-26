ALTER TABLE cc_outbound_task
    ADD COLUMN outbound_line_policy_id BIGINT NULL COMMENT '自动外呼任务指定的外呼线路策略ID' AFTER caller_number_id,
    ADD INDEX idx_outbound_task_line_policy (tenant_id, outbound_line_policy_id, deleted);
