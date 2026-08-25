-- 自动外呼第五阶段：运行指标、结果回写和失败分类。

ALTER TABLE cc_outbound_task
    ADD COLUMN result_writeback_enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否写入客户跟进记录' AFTER schedule_timezone,
    ADD COLUMN connected_tag VARCHAR(64) NULL COMMENT '接通后回写的客户标签' AFTER result_writeback_enabled,
    ADD COLUMN failed_tag VARCHAR(64) NULL COMMENT '未接通后回写的客户标签' AFTER connected_tag;

ALTER TABLE cc_outbound_attempt
    ADD COLUMN failure_category VARCHAR(24) NULL COMMENT '失败分类：CUSTOMER、NUMBER、NETWORK、PLATFORM、UNKNOWN' AFTER hangup_cause,
    ADD COLUMN retryable TINYINT(1) NULL COMMENT '本次失败是否具备重试条件' AFTER failure_category,
    ADD KEY idx_cc_outbound_attempt_monitor (tenant_id, task_id, started_at, answered_at),
    ADD KEY idx_cc_outbound_attempt_failure (tenant_id, task_id, failure_category, retryable);

ALTER TABLE cc_customer_follow_up
    ADD COLUMN source_type VARCHAR(32) NULL COMMENT '跟进来源类型' AFTER follow_up_by_name,
    ADD COLUMN source_id BIGINT NULL COMMENT '来源业务记录ID' AFTER source_type,
    ADD UNIQUE KEY uk_cc_customer_follow_up_source (tenant_id, source_type, source_id, deleted);
