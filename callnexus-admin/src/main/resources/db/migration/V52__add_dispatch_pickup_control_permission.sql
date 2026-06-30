-- 调度强接权限。

INSERT IGNORE INTO sys_menu VALUES('9307', '调度强接', '9300', '7', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:dispatch-control:pickup', '#', 103, 1, SYSDATE(), NULL, NULL, '将指定分机的振铃来电强接到当前调度员分机');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9307'
FROM sys_role_menu
WHERE menu_id IN ('9300', '9301');
