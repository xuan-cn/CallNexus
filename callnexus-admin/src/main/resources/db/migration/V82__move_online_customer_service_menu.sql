-- 将在线客服从业务运营中拆分为独立目录。
-- 兼容已经执行 V81 的数据库，不修改页面路径和权限标识。

INSERT IGNORE INTO sys_menu VALUES(
    '9510', '在线客服', '9000', '7', 'callcenter-chat', NULL, '', 1, 0, 'M', '0', '0',
    '', 'mdi:customer-service', 103, 1, SYSDATE(), NULL, NULL, '网站在线客服、访客会话和服务工作台'
);

UPDATE sys_menu
SET parent_id = '9510', order_num = '1'
WHERE menu_id = '9500';

UPDATE sys_menu
SET parent_id = '9510', order_num = '2'
WHERE menu_id = '9501';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9510'
FROM sys_role_menu
WHERE menu_id IN ('9500', '9501');
