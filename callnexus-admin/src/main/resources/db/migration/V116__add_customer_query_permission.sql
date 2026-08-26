-- 客户查询权限。坐席辅助接口依赖该权限，旧菜单只提供了客户页面和新增权限。
SET @customer_menu_id := COALESCE(
    (SELECT menu_id FROM sys_menu WHERE component = 'callcenter/customer/index' LIMIT 1),
    (SELECT menu_id FROM sys_menu WHERE path = 'customer' AND menu_type = 'C' LIMIT 1),
    9004
);

SET @customer_query_menu_id := (
    SELECT menu_id
    FROM sys_menu
    WHERE perms = 'callcenter:customer:query'
    LIMIT 1
);

SET @customer_query_menu_id := COALESCE(
    @customer_query_menu_id,
    (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu)
);

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, query_param,
    is_frame, is_cache, menu_type, visible, status, perms, icon,
    create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT
    @customer_query_menu_id, '客户查询', @customer_menu_id, 1, '', '', '',
    1, 0, 'F', '0', '0', 'callcenter:customer:query', '#',
    103, 1, SYSDATE(), NULL, NULL, '客户列表、来电弹屏和坐席辅助查询权限'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE perms = 'callcenter:customer:query'
);

-- 已经拥有客户页面的角色继续保留查询能力，无需逐个角色重新勾选。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role_id, @customer_query_menu_id
FROM sys_role_menu
WHERE menu_id = @customer_menu_id;

-- 纯坐席角色可能没有客户列表菜单，但来电工作台和坐席辅助同样需要查询客户。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT user_role.role_id, @customer_query_menu_id
FROM cc_agent agent
JOIN sys_user_role user_role ON user_role.user_id = agent.user_id
WHERE agent.enabled = 1
  AND agent.deleted = 0
  AND agent.user_id IS NOT NULL;
