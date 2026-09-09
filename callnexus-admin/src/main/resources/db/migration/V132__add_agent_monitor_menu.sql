-- 坐席监控按坐席和当日接听/振铃时间高频聚合。
SET @agent_answered_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'cc_call_leg'
      AND index_name = 'idx_cc_call_leg_agent_answered'
);
SET @agent_answered_index_sql = IF(
    @agent_answered_index_exists = 0,
    'ALTER TABLE cc_call_leg ADD KEY idx_cc_call_leg_agent_answered (tenant_id, agent_id, answered_at)',
    'SELECT 1'
);
PREPARE agent_answered_index_stmt FROM @agent_answered_index_sql;
EXECUTE agent_answered_index_stmt;
DEALLOCATE PREPARE agent_answered_index_stmt;

SET @agent_ringing_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'cc_call_leg'
      AND index_name = 'idx_cc_call_leg_agent_ringing'
);
SET @agent_ringing_index_sql = IF(
    @agent_ringing_index_exists = 0,
    'ALTER TABLE cc_call_leg ADD KEY idx_cc_call_leg_agent_ringing (tenant_id, agent_id, ringing_at)',
    'SELECT 1'
);
PREPARE agent_ringing_index_stmt FROM @agent_ringing_index_sql;
EXECUTE agent_ringing_index_stmt;
DEALLOCATE PREPARE agent_ringing_index_stmt;

-- 坐席监控放在“通话监控”目录下，权限沿用现有队列监控角色范围。
INSERT IGNORE INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, query_param,
    is_frame, is_cache, menu_type, visible, status, perms, icon,
    create_dept, create_by, create_time, update_by, update_time, remark
) VALUES (
    2096500000000000010, '坐席监控', 2083005045019787265, 5, 'agent-monitor',
    'callcenter/agent-monitor/index', '', 1, 0, 'C', '0', '0',
    'callcenter:agent-monitor:list', 'peoples', 103, 1, SYSDATE(), NULL, NULL,
    '坐席实时状态和今日个人通话统计'
);

INSERT IGNORE INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, query_param,
    is_frame, is_cache, menu_type, visible, status, perms, icon,
    create_dept, create_by, create_time, update_by, update_time, remark
) VALUES (
    2096500000000000011, '坐席监控查询', 2096500000000000010, 1, '', '', '',
    1, 0, 'F', '0', '0', 'callcenter:agent-monitor:query', '#',
    103, 1, SYSDATE(), NULL, NULL, ''
);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 2096500000000000010
FROM sys_role_menu
WHERE menu_id IN (9145, 2083005045019787265);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 2096500000000000011
FROM sys_role_menu
WHERE menu_id IN (9145, 9146, 2096500000000000010);
