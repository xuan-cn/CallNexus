-- 修复外呼任务逻辑删除时与历史已删除记录发生唯一索引冲突的问题。
-- MySQL 唯一索引允许存在多个 NULL，因此生成列仅在记录未删除时返回业务唯一键；
-- 已删除记录的生成列为 NULL，既保留历史数据，也允许后续重新使用相同业务键。

ALTER TABLE cc_outbound_task
    DROP INDEX uk_cc_outbound_task_code,
    ADD COLUMN active_task_code VARCHAR(32)
        GENERATED ALWAYS AS (CASE WHEN deleted = 0 THEN task_code ELSE NULL END) STORED
        COMMENT '未删除任务编码，用于逻辑删除唯一约束',
    ADD UNIQUE KEY uk_cc_outbound_task_active_code (tenant_id, active_task_code);

ALTER TABLE cc_outbound_member
    DROP INDEX uk_cc_outbound_member_customer,
    ADD COLUMN active_customer_id BIGINT
        GENERATED ALWAYS AS (CASE WHEN deleted = 0 THEN customer_id ELSE NULL END) STORED
        COMMENT '未删除客户ID，用于任务名单逻辑删除唯一约束',
    ADD UNIQUE KEY uk_cc_outbound_member_active_customer (tenant_id, task_id, active_customer_id);

ALTER TABLE cc_outbound_attempt
    DROP INDEX uk_cc_outbound_attempt_call,
    ADD COLUMN active_business_call_id VARCHAR(64)
        GENERATED ALWAYS AS (CASE WHEN deleted = 0 THEN business_call_id ELSE NULL END) STORED
        COMMENT '未删除业务通话ID，用于拨打尝试逻辑删除唯一约束',
    ADD UNIQUE KEY uk_cc_outbound_attempt_active_call (tenant_id, active_business_call_id);
