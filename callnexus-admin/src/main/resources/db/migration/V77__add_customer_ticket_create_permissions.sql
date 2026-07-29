-- 客户和工单手动新增权限。
INSERT IGNORE INTO sys_menu VALUES(
    '9490', '客户新增', '9004', '1', '', '', '', 1, 0, 'F', '0', '0',
    'callcenter:customer:create', '#', 103, 1, SYSDATE(), NULL, NULL, ''
);

INSERT IGNORE INTO sys_menu VALUES(
    '9491', '工单新增', '9005', '1', '', '', '', 1, 0, 'F', '0', '0',
    'callcenter:ticket:create', '#', 103, 1, SYSDATE(), NULL, NULL, ''
);

-- 保持升级前已有客户/工单菜单角色的操作能力。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role_id, '9490'
FROM sys_role_menu
WHERE menu_id = '9004';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role_id, '9491'
FROM sys_role_menu
WHERE menu_id = '9005';
