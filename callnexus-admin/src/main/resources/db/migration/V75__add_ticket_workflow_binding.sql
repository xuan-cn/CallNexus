-- 工单模板绑定工作流，并在工单中保存流程运行快照。
ALTER TABLE cc_form_template
    ADD COLUMN workflow_code VARCHAR(64) NULL COMMENT '工单模板绑定的已发布流程编码' AFTER business_type,
    ADD KEY idx_cc_form_template_workflow (tenant_id, business_type, workflow_code);

ALTER TABLE cc_ticket
    ADD COLUMN workflow_code VARCHAR(64) NULL COMMENT '创建工单时固化的流程编码' AFTER template_id,
    ADD COLUMN process_status VARCHAR(32) NULL COMMENT '工作流状态：draft、waiting、back、finish等' AFTER workflow_code,
    ADD COLUMN flow_instance_id BIGINT NULL COMMENT '工作流实例ID' AFTER process_status,
    ADD COLUMN current_node_code VARCHAR(64) NULL COMMENT '当前流程节点编码' AFTER flow_instance_id,
    ADD COLUMN current_node_name VARCHAR(128) NULL COMMENT '当前流程节点名称' AFTER current_node_code,
    ADD COLUMN submitted_at DATETIME NULL COMMENT '提交时间' AFTER current_node_name,
    ADD COLUMN resolved_at DATETIME NULL COMMENT '流程解决时间' AFTER submitted_at,
    ADD COLUMN closed_at DATETIME NULL COMMENT '工单关闭时间' AFTER resolved_at,
    ADD KEY idx_cc_ticket_process (tenant_id, process_status, ticket_status),
    ADD KEY idx_cc_ticket_flow_instance (tenant_id, flow_instance_id);
