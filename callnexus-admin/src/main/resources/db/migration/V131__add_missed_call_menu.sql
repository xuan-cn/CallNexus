-- 独立未接来电菜单，沿用通话记录的查询与详情权限。
INSERT IGNORE INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, query_param,
    is_frame, is_cache, menu_type, visible, status, perms, icon,
    create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT
    2096500000000000001, '未接来电', parent_id, order_num + 1, 'missed-call',
    'callcenter/missed-call/index', '', 1, 0, 'C', '0', '0',
    'callcenter:call-record:list', 'phone', 103, 1, SYSDATE(), NULL, NULL,
    '仅分页展示已结束且没有接听时间的呼入电话'
FROM sys_menu
WHERE menu_id = 9009;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role_id, 2096500000000000001
FROM sys_role_menu
WHERE menu_id = 9009;
