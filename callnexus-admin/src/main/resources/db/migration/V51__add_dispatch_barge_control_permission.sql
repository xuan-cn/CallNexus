-- 调度强插权限。

INSERT IGNORE INTO sys_menu VALUES('9306', '调度强插', '9300', '6', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:dispatch-control:barge', '#', 103, 1, SYSDATE(), NULL, NULL, '调度员加入坐席与客户的三方通话');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9306'
FROM sys_role_menu
WHERE menu_id IN ('9300', '9301');
