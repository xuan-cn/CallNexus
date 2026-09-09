-- Statistics reports. IDs were allocated after checking the current development sys_menu maximum.
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, query_param,
    is_frame, is_cache, menu_type, visible, status, perms, icon,
    create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT '2096600000000000000', '统计报表', '0', 9, 'callnexus-report', NULL, '',
       1, 0, 'M', '0', '0', '', 'chart', 103, 1, SYSDATE(), NULL, NULL, '呼叫中心统计报表'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE parent_id = '0' AND path = 'callnexus-report');

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, query_param,
    is_frame, is_cache, menu_type, visible, status, perms, icon,
    create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT '2096600000000000001', '运营总览', '2096600000000000000', 1, 'overview', 'callcenter/report-overview/index', '',
       1, 0, 'C', '0', '0', 'callcenter:report-overview:view', 'dashboard', 103, 1, SYSDATE(), NULL, NULL, '整体话务与接通趋势'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE parent_id = '2096600000000000000' AND path = 'overview');

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, query_param,
    is_frame, is_cache, menu_type, visible, status, perms, icon,
    create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT '2096600000000000002', '通话分析', '2096600000000000000', 2, 'calls', 'callcenter/report-call/index', '',
       1, 0, 'C', '0', '0', 'callcenter:report-call:view', 'phone', 103, 1, SYSDATE(), NULL, NULL, '通话趋势、结果与明细'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE parent_id = '2096600000000000000' AND path = 'calls');

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, query_param,
    is_frame, is_cache, menu_type, visible, status, perms, icon,
    create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT '2096600000000000003', '坐席分析', '2096600000000000000', 3, 'agents', 'callcenter/report-agent/index', '',
       1, 0, 'C', '0', '0', 'callcenter:report-agent:view', 'peoples', 103, 1, SYSDATE(), NULL, NULL, '坐席处理量和状态时长'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE parent_id = '2096600000000000000' AND path = 'agents');

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, query_param,
    is_frame, is_cache, menu_type, visible, status, perms, icon,
    create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT '2096600000000000004', '队列分析', '2096600000000000000', 4, 'queues', 'callcenter/report-queue/index', '',
       1, 0, 'C', '0', '0', 'callcenter:report-queue:view', 'list', 103, 1, SYSDATE(), NULL, NULL, '队列流量、等待和服务水平'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE parent_id = '2096600000000000000' AND path = 'queues');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '2096600000000000000' FROM sys_role_menu WHERE menu_id IN ('9009', '9145');
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '2096600000000000001' FROM sys_role_menu WHERE menu_id IN ('9009', '9145');
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '2096600000000000002' FROM sys_role_menu WHERE menu_id = '9009';
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '2096600000000000003' FROM sys_role_menu WHERE menu_id IN (SELECT menu_id FROM sys_menu WHERE path = 'agent-monitor');
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '2096600000000000004' FROM sys_role_menu WHERE menu_id = '9145';
