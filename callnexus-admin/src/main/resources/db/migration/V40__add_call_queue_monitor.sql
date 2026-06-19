-- 队列实时监控索引和菜单
-- 统计当前排队、接通、放弃、超时和趋势时，会频繁按队列、事件类型和发生时间过滤。

ALTER TABLE cc_call_session
    ADD KEY idx_cc_call_session_queue_started (tenant_id, handling_queue_id, started_at),
    ADD KEY idx_cc_call_session_status_started (tenant_id, call_status, started_at);

ALTER TABLE cc_call_event
    ADD KEY idx_cc_call_event_type_time (tenant_id, event_type, occurred_at),
    ADD KEY idx_cc_call_event_session_type (tenant_id, session_id, event_type, occurred_at);

INSERT IGNORE INTO sys_menu VALUES('9145', '队列监控', '9200', '6', 'call-queue-monitor', 'callcenter/call-queue-monitor/index', '', 1, 0, 'C', '0', '0', 'callcenter:queue-monitor:list', 'monitor', 103, 1, sysdate(), null, null, '呼叫队列实时监控菜单');
INSERT IGNORE INTO sys_menu VALUES('9146', '队列监控查询', '9145', '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:queue-monitor:query', '#', 103, 1, sysdate(), null, null, '');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9145'
FROM sys_role_menu
WHERE menu_id IN ('9140', '9200');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9146'
FROM sys_role_menu
WHERE menu_id IN ('9140', '9145');
