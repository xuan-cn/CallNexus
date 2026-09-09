-- 客户删除按钮权限。仅创建权限项，不自动授予普通角色。
SET @customer_menu_id := COALESCE(
    (SELECT menu_id FROM sys_menu WHERE component = 'callcenter/customer/index' LIMIT 1),
    (SELECT menu_id FROM sys_menu WHERE path = 'customer' AND menu_type = 'C' LIMIT 1),
    9004
);

SET @customer_delete_menu_id := (
    SELECT menu_id FROM sys_menu WHERE perms = 'callcenter:customer:delete' LIMIT 1
);
SET @customer_delete_menu_id := COALESCE(
    @customer_delete_menu_id,
    (SELECT COALESCE(MAX(menu_id), 0) + 1 FROM sys_menu)
);

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, query_param,
    is_frame, is_cache, menu_type, visible, status, perms, icon,
    create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT
    @customer_delete_menu_id, '客户删除', @customer_menu_id, 9, '', '', '',
    1, 0, 'F', '0', '0', 'callcenter:customer:delete', '#',
    103, 1, SYSDATE(), NULL, NULL, '删除客户主档、号码、归属、跟进和动态表单数据'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE perms = 'callcenter:customer:delete'
);
