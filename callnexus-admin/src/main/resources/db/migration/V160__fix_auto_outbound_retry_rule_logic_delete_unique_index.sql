-- 修复自动外呼任务重复修改时，历史重试规则发生唯一键冲突的问题。
-- MySQL 唯一索引允许多个 NULL，因此仅让未删除记录生成业务唯一键。

ALTER TABLE cc_outbound_task_retry_rule
    DROP INDEX uk_cc_outbound_retry_rule,
    ADD COLUMN active_result_code VARCHAR(32)
        GENERATED ALWAYS AS (CASE WHEN deleted = 0 THEN result_code ELSE NULL END) STORED
        COMMENT '未删除重试结果编码，用于逻辑删除唯一约束',
    ADD UNIQUE KEY uk_cc_outbound_retry_rule_active
        (tenant_id, task_id, active_result_code);
