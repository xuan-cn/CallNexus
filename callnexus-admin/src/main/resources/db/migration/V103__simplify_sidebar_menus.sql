-- 菜单精简（第一版）：业务中心收拢 + 隐藏空/低频项。

INSERT IGNORE INTO sys_menu VALUES (
    '9900', '业务中心', '0', '1', 'biz-center', NULL, '', 1, 0, 'M', '0', '0', '',
    'mdi:briefcase-outline', 103, 1, SYSDATE(), NULL, NULL, '任务、客户、工单与表单'
);

UPDATE sys_menu SET parent_id = '9900', order_num = 1 WHERE menu_id = '9600';
UPDATE sys_menu SET parent_id = '9900', order_num = 2, menu_name = '客户', icon = 'mdi:account-box' WHERE menu_id = '9004';
UPDATE sys_menu SET parent_id = '9900', order_num = 3, menu_name = '工单', icon = 'list' WHERE menu_id = '9005';
UPDATE sys_menu SET parent_id = '9900', order_num = 4 WHERE menu_id = '11618';
UPDATE sys_menu SET parent_id = '9900', order_num = 5, menu_name = '表单模板' WHERE menu_id = '9006';

UPDATE sys_menu SET visible = '1', order_num = 92, path = 'customer-dir-legacy' WHERE menu_id = '2083002869484634113';
UPDATE sys_menu SET visible = '1', order_num = 93, path = 'ticket-dir-legacy' WHERE menu_id = '2083003767048912898';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9900'
FROM sys_role_menu
WHERE menu_id IN (
    '9600', '9004', '9005', '11618', '9006',
    '2083002869484634113', '2083003767048912898'
);

UPDATE sys_menu SET visible = '1', order_num = 94 WHERE menu_id = '9700';
UPDATE sys_menu SET visible = '1' WHERE menu_id = '9470';
UPDATE sys_menu SET visible = '1' WHERE menu_id = '9480';
UPDATE sys_menu SET visible = '1' WHERE menu_id = '9110';
UPDATE sys_menu SET visible = '1' WHERE menu_id = '9003';
UPDATE sys_menu SET visible = '1' WHERE menu_id = '104';
UPDATE sys_menu SET visible = '1' WHERE menu_id = '123';
UPDATE sys_menu SET visible = '1' WHERE menu_id = '107';

UPDATE sys_menu SET order_num = 1 WHERE menu_id = '9900';
UPDATE sys_menu SET order_num = 2 WHERE menu_id = '9510';
UPDATE sys_menu SET order_num = 3 WHERE menu_id = '9800';
UPDATE sys_menu SET order_num = 4 WHERE menu_id = '2083002003578961922';
UPDATE sys_menu SET order_num = 5 WHERE menu_id = '9320';
UPDATE sys_menu SET order_num = 6 WHERE menu_id = '1';

UPDATE sys_menu SET order_num = 1 WHERE menu_id = '9001';
UPDATE sys_menu SET order_num = 2 WHERE menu_id = '9002';
UPDATE sys_menu SET order_num = 3 WHERE menu_id = '9007';
UPDATE sys_menu SET order_num = 4 WHERE menu_id = '9290';
UPDATE sys_menu SET order_num = 5 WHERE menu_id = '9130';
UPDATE sys_menu SET order_num = 6 WHERE menu_id = '9008';
UPDATE sys_menu SET order_num = 7 WHERE menu_id = '9550';
UPDATE sys_menu SET order_num = 8 WHERE menu_id = '9120';
UPDATE sys_menu SET order_num = 9 WHERE menu_id = '9140';
UPDATE sys_menu SET order_num = 10 WHERE menu_id = '9250';
UPDATE sys_menu SET order_num = 11 WHERE menu_id = '9100';
UPDATE sys_menu SET order_num = 12 WHERE menu_id = '2087811471374311425';
UPDATE sys_menu SET order_num = 13 WHERE menu_id = '9270';
