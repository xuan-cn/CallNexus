-- Queue hangup satisfaction report.
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, query_param,
    is_frame, is_cache, menu_type, visible, status, perms, icon,
    create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT '2096600000000000007', '满意度分析', parent.menu_id, 6, 'satisfaction',
       'callcenter/report-satisfaction/index', '', 1, 0, 'C', '0', '0',
       'callcenter:report-satisfaction:view', 'star', 103, 1, SYSDATE(), NULL, NULL,
       '挂机评价参与率、评分分布以及队列和坐席满意度'
FROM sys_menu parent
WHERE parent.parent_id = '0' AND parent.path = 'callnexus-report'
  AND NOT EXISTS (
    SELECT 1 FROM sys_menu existing
    WHERE existing.parent_id = parent.menu_id AND existing.path = 'satisfaction'
  )
LIMIT 1;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role_menu.role_id, satisfaction_menu.menu_id
FROM sys_role_menu role_menu
JOIN sys_menu report_root ON report_root.menu_id = role_menu.menu_id AND report_root.path = 'callnexus-report'
JOIN sys_menu satisfaction_menu ON satisfaction_menu.parent_id = report_root.menu_id
    AND satisfaction_menu.path = 'satisfaction';
