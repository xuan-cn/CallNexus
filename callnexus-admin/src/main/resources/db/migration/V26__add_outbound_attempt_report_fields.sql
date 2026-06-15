ALTER TABLE cc_outbound_attempt
    ADD COLUMN task_name VARCHAR(64) NULL COMMENT '外呼任务名称快照' AFTER customer_id,
    ADD COLUMN customer_name VARCHAR(128) NULL COMMENT '客户名称快照' AFTER task_name,
    ADD COLUMN phone_number VARCHAR(32) NULL COMMENT '外呼号码快照' AFTER customer_name,
    ADD COLUMN suggested_result_code VARCHAR(32) NULL COMMENT '系统建议的外呼结果编码' AFTER result_remark;

UPDATE cc_outbound_attempt attempt
JOIN cc_outbound_member member
    ON member.tenant_id = attempt.tenant_id
    AND member.id = attempt.member_id
    AND member.deleted = 0
JOIN cc_outbound_task task
    ON task.tenant_id = attempt.tenant_id
    AND task.id = attempt.task_id
    AND task.deleted = 0
SET attempt.task_name = task.task_name,
    attempt.customer_name = member.customer_name,
    attempt.phone_number = member.phone_number
WHERE attempt.deleted = 0;
