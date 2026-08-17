-- AI通话记录菜单。
INSERT IGNORE INTO sys_menu VALUES('9360', 'AI通话记录', '9320', '6', 'ai-call-record', 'callcenter/ai-call-record/index', '', 1, 0, 'C', '0', '0', 'callcenter:ai-call-record:list', 'phone', 103, 1, SYSDATE(), NULL, NULL, '只查看AI参与的通话记录、录音和对话内容');
INSERT IGNORE INTO sys_menu VALUES('9361', 'AI通话记录查询', '9360', '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-call-record:list', '#', 103, 1, SYSDATE(), NULL, NULL, '');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, menu_id FROM (
    SELECT role_id, '9360' menu_id FROM sys_role_menu WHERE menu_id IN ('9320', '9340', '9350')
    UNION ALL SELECT role_id, '9361' FROM sys_role_menu WHERE menu_id IN ('9320', '9340', '9350')
) permissions;
