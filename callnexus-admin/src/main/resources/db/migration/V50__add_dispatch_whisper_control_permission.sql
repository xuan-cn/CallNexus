-- 调度耳语权限。

INSERT IGNORE INTO sys_menu VALUES('9305', '调度耳语', '9300', '5', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:dispatch-control:whisper', '#', 103, 1, SYSDATE(), NULL, NULL, '调度员与指定坐席耳语，客户不可听见');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9305'
FROM sys_role_menu
WHERE menu_id IN ('9300', '9301');
