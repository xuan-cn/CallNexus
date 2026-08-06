-- 开放接口应用管理页面，并将 V84 中的按钮权限挂到页面下。
INSERT IGNORE INTO sys_menu VALUES(
    '9530', '开放接口应用', '9000', '83', 'openapi-application', 'callcenter/openapi-application/index', '',
    1, 0, 'C', '0', '0', 'callcenter:openapi-application:list', 'connection', 103, 1, SYSDATE(), NULL, NULL,
    '第三方开放应用、凭证、Scope、IP白名单和线路授权'
);

UPDATE sys_menu SET parent_id = '9530', order_num = '1' WHERE menu_id = '9520';
UPDATE sys_menu SET parent_id = '9530', order_num = '2' WHERE menu_id = '9521';
UPDATE sys_menu SET parent_id = '9530', order_num = '3' WHERE menu_id = '9522';
UPDATE sys_menu SET parent_id = '9530', order_num = '4' WHERE menu_id = '9523';
UPDATE sys_menu SET parent_id = '9530', order_num = '5' WHERE menu_id = '9524';
UPDATE sys_menu SET parent_id = '9530', order_num = '6' WHERE menu_id = '9525';
