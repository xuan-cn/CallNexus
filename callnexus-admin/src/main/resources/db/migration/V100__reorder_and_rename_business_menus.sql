-- 整理业务侧栏菜单命名与排序。
-- 仅调整 menu_name / parent_id / order_num / visible，不改 path、component、perms。

-- ========== 一级菜单排序 ==========
-- 业务前段：任务、客户、工单、报表、在线客服
UPDATE sys_menu SET order_num = 1 WHERE menu_id = '9600'; -- 任务管理
UPDATE sys_menu SET order_num = 2 WHERE menu_id = '2083002869484634113'; -- 客户管理
UPDATE sys_menu SET order_num = 3 WHERE menu_id = '2083003767048912898'; -- 工单管理

-- 报表管理（目录，页面后续再挂）
INSERT IGNORE INTO sys_menu VALUES (
    '9700', '报表管理', '0', '4', 'report-management', NULL, '', 1, 0, 'M', '0', '0', '',
    'chart', 103, 1, SYSDATE(), NULL, NULL, '报表管理目录'
);

UPDATE sys_menu SET order_num = 5 WHERE menu_id = '9510'; -- 在线客服
UPDATE sys_menu SET order_num = 6 WHERE menu_id = '2083002003578961922'; -- IPPBX管理
UPDATE sys_menu SET order_num = 7 WHERE menu_id = '2083004028815425537'; -- 外呼管理
UPDATE sys_menu SET order_num = 8 WHERE menu_id = '9320'; -- AI策略管理
UPDATE sys_menu SET order_num = 9 WHERE menu_id = '2083004791084040194'; -- 通话管理
UPDATE sys_menu SET order_num = 10 WHERE menu_id = '2083005045019787265'; -- 通话监控
UPDATE sys_menu SET order_num = 11 WHERE menu_id = '11616'; -- 工作流
UPDATE sys_menu SET order_num = 12 WHERE menu_id = '11618'; -- 我的任务

-- 媒体目录已并入 IPPBX，隐藏空的一级媒体管理
UPDATE sys_menu SET visible = '1', order_num = 95 WHERE menu_id = '2083004370823168001';

-- 末尾：第三方接入、系统管理
UPDATE sys_menu SET order_num = 98 WHERE menu_id = '2084115734757126146'; -- 第三方接入
UPDATE sys_menu SET order_num = 99 WHERE menu_id = '1'; -- 系统管理

-- 其余系统类菜单靠后，避免插到业务前面
UPDATE sys_menu SET order_num = 90 WHERE menu_id = '2'; -- 系统监控
UPDATE sys_menu SET order_num = 91 WHERE menu_id = '6'; -- 租户管理
UPDATE sys_menu SET order_num = 92 WHERE menu_id = '3'; -- 系统工具
UPDATE sys_menu SET order_num = 93 WHERE menu_id = '5'; -- 流程测试
UPDATE sys_menu SET order_num = 94 WHERE menu_id = '9000'; -- 管理中心（已隐藏）

-- ========== IPPBX 子菜单：改名 + 排序 ==========
UPDATE sys_menu SET menu_name = '分机管理', order_num = 1 WHERE menu_id = '9001';
UPDATE sys_menu SET menu_name = '线路管理', order_num = 2 WHERE menu_id = '9007';
UPDATE sys_menu SET menu_name = '线路组', order_num = 3 WHERE menu_id = '9290';
UPDATE sys_menu SET menu_name = '呼入呼出组', order_num = 4 WHERE menu_id = '9130';
UPDATE sys_menu SET menu_name = '呼入配置', order_num = 5 WHERE menu_id = '9008';
UPDATE sys_menu SET menu_name = 'IVR配置', order_num = 6 WHERE menu_id = '9120';
UPDATE sys_menu SET order_num = 7 WHERE menu_id = '9250'; -- 工作时间

-- 媒体管理并入 IPPBX
UPDATE sys_menu
SET menu_name = '媒体管理',
    parent_id = '2083002003578961922',
    order_num = 8
WHERE menu_id = '9100';

UPDATE sys_menu SET order_num = 9 WHERE menu_id = '9140'; -- 队列管理
UPDATE sys_menu SET order_num = 10 WHERE menu_id = '9550'; -- DID入口管理
UPDATE sys_menu SET menu_name = '黑白名单配置', order_num = 11
WHERE menu_id = '2087811471374311425'; -- 原访问控制ACL
UPDATE sys_menu SET order_num = 12 WHERE menu_id = '9003'; -- 话机服务配置
UPDATE sys_menu SET order_num = 13 WHERE menu_id = '9110'; -- 节点组管理
UPDATE sys_menu SET order_num = 14 WHERE menu_id = '9006'; -- 表单模板
UPDATE sys_menu SET order_num = 15 WHERE menu_id = '9270'; -- 配置中心
UPDATE sys_menu SET order_num = 16 WHERE menu_id = '9470'; -- 区号维护
UPDATE sys_menu SET order_num = 17 WHERE menu_id = '9480'; -- 手机号段
UPDATE sys_menu SET order_num = 99 WHERE menu_id = '9002'; -- 坐席管理放最后

-- 已有呼叫中心相关菜单权限的角色，自动获得报表目录
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9700'
FROM sys_role_menu
WHERE menu_id IN (
    '9600',
    '2083002869484634113',
    '2083003767048912898',
    '9510',
    '2083002003578961922'
);
