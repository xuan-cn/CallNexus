-- 呼叫中心菜单按业务职责分类。
-- 仅调整菜单层级和排序，不修改页面路径、组件路径及权限标识。

INSERT INTO sys_menu VALUES('9200', '业务运营', '9000', '1', 'callcenter-operation', NULL, '', 1, 0, 'M', '0', '0', '', 'monitor', 103, 1, SYSDATE(), NULL, NULL, '坐席、客户、工单和通话记录');
INSERT INTO sys_menu VALUES('9201', '呼叫策略', '9000', '2', 'callcenter-routing', NULL, '', 1, 0, 'M', '0', '0', '', 'share', 103, 1, SYSDATE(), NULL, NULL, 'IVR、技能组和呼叫队列');
INSERT INTO sys_menu VALUES('9202', '号码与媒体', '9000', '3', 'callcenter-number-media', NULL, '', 1, 0, 'M', '0', '0', '', 'phone', 103, 1, SYSDATE(), NULL, NULL, '号码和声音媒体');
INSERT INTO sys_menu VALUES('9203', '通信资源', '9000', '4', 'callcenter-telephony-resource', NULL, '', 1, 0, 'M', '0', '0', '', 'connection', 103, 1, SYSDATE(), NULL, NULL, 'SIP账号、话机服务和网关');
INSERT INTO sys_menu VALUES('9204', '节点运维', '9000', '5', 'callcenter-node-operation', NULL, '', 1, 0, 'M', '0', '0', '', 'server', 103, 1, SYSDATE(), NULL, NULL, 'FreeSWITCH节点和节点组');

-- 业务运营
UPDATE sys_menu SET parent_id = '9200', order_num = '1' WHERE menu_id = '9002';
UPDATE sys_menu SET parent_id = '9200', order_num = '2' WHERE menu_id = '9004';
UPDATE sys_menu SET parent_id = '9200', order_num = '3' WHERE menu_id = '9005';
UPDATE sys_menu SET parent_id = '9200', order_num = '4' WHERE menu_id = '9009';
UPDATE sys_menu SET parent_id = '9200', order_num = '5' WHERE menu_id = '9006';

-- 呼叫策略
UPDATE sys_menu SET parent_id = '9201', order_num = '1' WHERE menu_id = '9120';
UPDATE sys_menu SET parent_id = '9201', order_num = '2' WHERE menu_id = '9130';
UPDATE sys_menu SET parent_id = '9201', order_num = '3' WHERE menu_id = '9140';

-- 号码与媒体
UPDATE sys_menu SET parent_id = '9202', order_num = '1' WHERE menu_id = '9008';
UPDATE sys_menu SET parent_id = '9202', order_num = '2' WHERE menu_id = '9100';

-- 通信资源
UPDATE sys_menu SET parent_id = '9203', order_num = '1' WHERE menu_id = '9001';
UPDATE sys_menu SET parent_id = '9203', order_num = '2' WHERE menu_name = '话机服务配置' AND menu_type = 'C';
UPDATE sys_menu SET parent_id = '9203', order_num = '3' WHERE menu_id = '9007';

-- 节点运维
UPDATE sys_menu SET parent_id = '9204', order_num = '1' WHERE menu_id = '9003';
UPDATE sys_menu SET parent_id = '9204', order_num = '2' WHERE menu_id = '9110';

-- 已拥有呼叫中心子菜单的角色自动获得对应分类目录，避免非管理员角色看不到原有菜单。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9200'
FROM sys_role_menu
WHERE menu_id IN ('9002', '9004', '9005', '9006', '9009');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9201'
FROM sys_role_menu
WHERE menu_id IN ('9120', '9130', '9140');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9202'
FROM sys_role_menu
WHERE menu_id IN ('9008', '9100');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9203'
FROM sys_role_menu
WHERE menu_id IN ('9001', '9007')
   OR menu_id IN (SELECT menu_id FROM sys_menu WHERE menu_name = '话机服务配置' AND menu_type = 'C');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9204'
FROM sys_role_menu
WHERE menu_id IN ('9003', '9110');
