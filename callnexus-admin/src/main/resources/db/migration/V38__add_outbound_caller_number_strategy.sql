ALTER TABLE cc_agent
    ADD COLUMN caller_number_id BIGINT NULL COMMENT '坐席默认外呼主叫号码ID' AFTER user_id;

ALTER TABLE cc_call_queue
    ADD COLUMN caller_number_id BIGINT NULL COMMENT '队列默认外呼主叫号码ID' AFTER wait_media_id;

ALTER TABLE cc_outbound_task
    ADD COLUMN caller_number_id BIGINT NULL COMMENT '外呼任务指定主叫号码ID' AFTER description;

CREATE INDEX idx_cc_agent_caller_number ON cc_agent (tenant_id, caller_number_id);
CREATE INDEX idx_cc_call_queue_caller_number ON cc_call_queue (tenant_id, caller_number_id);
CREATE INDEX idx_cc_outbound_task_caller_number ON cc_outbound_task (tenant_id, caller_number_id);
