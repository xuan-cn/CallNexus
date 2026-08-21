-- 收拢一级菜单：外呼/通话/监控 → 呼叫运营；工作流/系统监控/第三方 → 系统管理；我的任务 → 工单管理。

-- 新建「呼叫运营」
INSERT IGNORE INTO sys_menu VALUES (
    '9800', '呼叫运营', '0', '6', 'call-operation', NULL, '', 1, 0, 'M', '0', '0', '',
    'phone', 103, 1, SYSDATE(), NULL, NULL, '外呼、通话记录与监控'
);

-- 外呼 / 通话 / 监控 页面并入呼叫运营
UPDATE sys_menu SET parent_id = '9800', order_num = 1 WHERE menu_id = '9150'; -- 外呼任务
UPDATE sys_menu SET parent_id = '9800', order_num = 2 WHERE menu_id = '9160'; -- 外呼黑名单
UPDATE sys_menu SET parent_id = '9800', order_num = 3 WHERE menu_id = '9009'; -- 通话记录
UPDATE sys_menu SET parent_id = '9800', order_num = 4 WHERE menu_id = '9260'; -- 语音留言
UPDATE sys_menu SET parent_id = '9800', order_num = 5 WHERE menu_id = '9145'; -- 队列监控
UPDATE sys_menu SET parent_id = '9800', order_num = 6 WHERE menu_id = '9300'; -- 调度通话监控

-- 隐藏已空的一级目录
UPDATE sys_menu SET visible = '1', order_num = 96 WHERE menu_id = '2083004028815425537'; -- 外呼管理
UPDATE sys_menu SET visible = '1', order_num = 97 WHERE menu_id = '2083004791084040194'; -- 通话管理
UPDATE sys_menu SET visible = '1', order_num = 98 WHERE menu_id = '2083005045019787265'; -- 通话监控

-- 我的任务挂到工单管理下
UPDATE sys_menu SET parent_id = '2083003767048912898', order_num = 2 WHERE menu_id = '11618';
UPDATE sys_menu SET order_num = 1 WHERE menu_id = '9005'; -- 工单列表

-- 工作流、系统监控、第三方接入 并入系统管理
UPDATE sys_menu SET parent_id = '1', order_num = 20 WHERE menu_id = '2084115734757126146'; -- 第三方接入
UPDATE sys_menu SET parent_id = '1', order_num = 21 WHERE menu_id = '11616'; -- 工作流
UPDATE sys_menu SET parent_id = '1', order_num = 22 WHERE menu_id = '2'; -- 系统监控

-- 一级业务排序（首页除外）
UPDATE sys_menu SET order_num = 1 WHERE menu_id = '9600'; -- 任务管理
UPDATE sys_menu SET order_num = 2 WHERE menu_id = '2083002869484634113'; -- 客户管理
UPDATE sys_menu SET order_num = 3 WHERE menu_id = '2083003767048912898'; -- 工单管理
UPDATE sys_menu SET order_num = 4 WHERE menu_id = '9700'; -- 报表管理
UPDATE sys_menu SET order_num = 5 WHERE menu_id = '9510'; -- 在线客服
UPDATE sys_menu SET order_num = 6 WHERE menu_id = '9800'; -- 呼叫运营
UPDATE sys_menu SET order_num = 7 WHERE menu_id = '2083002003578961922'; -- IPPBX管理
UPDATE sys_menu SET order_num = 8 WHERE menu_id = '9320'; -- AI策略管理
UPDATE sys_menu SET order_num = 9 WHERE menu_id = '1'; -- 系统管理

-- 角色自动获得呼叫运营目录
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9800'
FROM sys_role_menu
WHERE menu_id IN (
    '2083004028815425537',
    '2083004791084040194',
    '2083005045019787265',
    '9150', '9160', '9009', '9260', '9145', '9300'
);
