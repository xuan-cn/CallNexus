-- 调度台第一批控制权限：强制挂断和强制转接到分机。

INSERT IGNORE INTO sys_menu VALUES('9302', '调度强制挂断', '9300', '2', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:dispatch-control:hangup', '#', 103, 1, SYSDATE(), NULL, NULL, '强制结束整通业务通话');
INSERT IGNORE INTO sys_menu VALUES('9303', '调度强制转接', '9300', '3', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:dispatch-control:transfer', '#', 103, 1, SYSDATE(), NULL, NULL, '将客户电话腿强制转接到指定分机');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9302'
FROM sys_role_menu
WHERE menu_id IN ('9300', '9301');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9303'
FROM sys_role_menu
WHERE menu_id IN ('9300', '9301');
