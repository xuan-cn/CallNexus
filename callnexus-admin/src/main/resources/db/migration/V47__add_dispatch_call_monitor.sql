-- 调度台第一阶段：活动通话与电话腿拓扑测试入口。

ALTER TABLE cc_call_leg
    ADD KEY idx_cc_call_leg_active_session (tenant_id, active, session_id);

ALTER TABLE cc_call_bridge
    ADD KEY idx_cc_call_bridge_state_session (tenant_id, bridge_state, session_id);

ALTER TABLE cc_agent_call_session
    ADD KEY idx_cc_agent_call_visible_session (tenant_id, visible, session_id);

INSERT IGNORE INTO sys_menu VALUES('9300', '调度通话监控', '9200', '9', 'dispatch-monitor', 'callcenter/dispatch-monitor/index', '', 1, 0, 'C', '0', '0', 'callcenter:dispatch-monitor:list', 'monitor', 103, 1, SYSDATE(), NULL, NULL, '调度台第一阶段活动通话和电话腿拓扑测试页面');
INSERT IGNORE INTO sys_menu VALUES('9301', '调度通话查询', '9300', '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:dispatch-monitor:query', '#', 103, 1, SYSDATE(), NULL, NULL, '');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9300'
FROM sys_role_menu
WHERE menu_id IN ('9145', '9200');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9301'
FROM sys_role_menu
WHERE menu_id IN ('9300', '9146');
