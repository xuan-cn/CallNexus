-- 数据大屏菜单入库，便于角色权限配置（替代前端写死菜单）。

INSERT IGNORE INTO sys_menu VALUES (
    '9910', '数据大屏', '0', '2', 'data-screen', NULL, '', 1, 0, 'M', '0', '0', '',
    'chart', 103, 1, SYSDATE(), NULL, NULL, '运营与 AI 数据大屏入口'
);

INSERT IGNORE INTO sys_menu VALUES (
    '9911', '首页大屏', '9910', '1', 'home', 'screen/ScreenLaunch', '{"screenPath":"/screen/home"}',
    1, 0, 'C', '0', '0', 'callcenter:screen:home', 'dashboard', 103, 1, SYSDATE(), NULL, NULL, '首页运营大屏'
);

INSERT IGNORE INTO sys_menu VALUES (
    '9912', 'AI 大屏', '9910', '2', 'ai', 'screen/ScreenLaunch', '{"screenPath":"/screen/ai"}',
    1, 0, 'C', '0', '0', 'callcenter:screen:ai', 'monitor', 103, 1, SYSDATE(), NULL, NULL, 'AI 运营大屏'
);

-- 超管
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
    (1, '9910'),
    (1, '9911'),
    (1, '9912');

-- 已有业务中心或 IPPBX 权限的角色一并开通
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9910'
FROM sys_role_menu
WHERE menu_id IN ('9900', '2083002003578961922');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9911'
FROM sys_role_menu
WHERE menu_id = '9910';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9912'
FROM sys_role_menu
WHERE menu_id = '9910';
