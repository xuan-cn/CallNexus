-- 调度监听权限。

INSERT IGNORE INTO sys_menu VALUES('9304', '调度监听', '9300', '4', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:dispatch-control:monitor', '#', 103, 1, SYSDATE(), NULL, NULL, '监听指定活动坐席电话腿');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9304'
FROM sys_role_menu
WHERE menu_id IN ('9300', '9301');
