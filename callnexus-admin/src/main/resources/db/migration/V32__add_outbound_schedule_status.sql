ALTER TABLE cc_outbound_task
    ADD COLUMN last_scheduled_at DATETIME NULL COMMENT '最近一次外呼维护调度时间' AFTER retry_result_codes,
    ADD COLUMN last_schedule_summary VARCHAR(255) NULL COMMENT '最近一次外呼维护调度结果摘要' AFTER last_scheduled_at;

