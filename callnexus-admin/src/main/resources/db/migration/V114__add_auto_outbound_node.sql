-- 自动外呼任务独立保存执行节点；外显号码为空时由节点外呼策略动态选线。

ALTER TABLE cc_outbound_task
    ADD COLUMN node_id BIGINT NULL COMMENT '自动外呼执行的FreeSWITCH节点ID' AFTER description,
    ADD KEY idx_cc_outbound_task_node (tenant_id, task_type, node_id, status);

UPDATE cc_outbound_task t
JOIN cc_phone_number p
  ON p.id = t.caller_number_id
 AND p.tenant_id = t.tenant_id
 AND p.deleted = 0
SET t.node_id = p.node_id
WHERE t.task_type = 'AUTO'
  AND t.node_id IS NULL;
