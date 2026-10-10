ALTER TABLE cc_outbound_task
    ADD COLUMN assignment_mode VARCHAR(16) NOT NULL DEFAULT 'PUBLIC' COMMENT '名单分配方式：PUBLIC公共领取、SPECIFIED指定坐席' AFTER description,
    ADD COLUMN assigned_agent_ids VARCHAR(2000) NULL COMMENT '指定坐席ID快照，逗号分隔' AFTER assignment_mode,
    ADD COLUMN customer_filter_json LONGTEXT NULL COMMENT '最近一次客户筛选条件快照' AFTER assigned_agent_ids;

ALTER TABLE cc_outbound_member
    ADD COLUMN assigned_agent_id BIGINT NULL COMMENT '预分配坐席ID' AFTER status,
    ADD KEY idx_cc_outbound_member_assigned (tenant_id, task_id, assigned_agent_id, status);

UPDATE sys_menu
SET menu_name = '点击拨号', remark = '客户筛选、坐席分配与预览式点击拨号'
WHERE menu_id = 9150 OR (path = 'outbound-task' AND component = 'callcenter/outbound-task/index');
