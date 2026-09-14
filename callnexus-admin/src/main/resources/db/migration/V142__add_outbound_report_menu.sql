-- Unified preview and automatic outbound reporting.
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, query_param,
    is_frame, is_cache, menu_type, visible, status, perms, icon,
    create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT '2096600000000000006', '外呼分析', parent.menu_id, 5, 'outbound',
       'callcenter/report-outbound/index', '', 1, 0, 'C', '0', '0',
       'callcenter:report-outbound:view', 'chart', 103, 1, SYSDATE(), NULL, NULL,
       '预览外呼与自动外呼统一统计'
FROM sys_menu parent
WHERE parent.parent_id = '0' AND parent.path = 'callnexus-report'
  AND NOT EXISTS (
    SELECT 1 FROM sys_menu existing
    WHERE existing.parent_id = parent.menu_id AND existing.path = 'outbound'
  )
LIMIT 1;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role_menu.role_id, outbound_menu.menu_id
FROM sys_role_menu role_menu
JOIN sys_menu report_root ON report_root.menu_id = role_menu.menu_id AND report_root.path = 'callnexus-report'
JOIN sys_menu outbound_menu ON outbound_menu.parent_id = report_root.menu_id AND outbound_menu.path = 'outbound';
