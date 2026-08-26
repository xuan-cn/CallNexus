-- 自动外呼重新执行：每轮调度使用独立轮次和起始时间，保留历史拨打审计。
ALTER TABLE cc_outbound_task
    ADD COLUMN execution_round INT NOT NULL DEFAULT 1 COMMENT '自动外呼执行轮次' AFTER scheduler_heartbeat_at,
    ADD COLUMN execution_started_at DATETIME NULL COMMENT '当前执行轮次开始时间' AFTER execution_round;
