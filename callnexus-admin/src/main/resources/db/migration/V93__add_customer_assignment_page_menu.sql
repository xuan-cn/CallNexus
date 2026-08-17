-- 客户资料分配独立页面。
INSERT IGNORE INTO sys_menu VALUES(
    '9540', '资料分配', '9004', '4', 'customer-assignment', 'callcenter/customer-assignment/index', '', 1, 0, 'C', '0', '0',
    'callcenter:customer:assign', 'peoples', 103, 1, SYSDATE(), NULL, NULL, '客户资料池与批量分配页面'
);

INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
SELECT DISTINCT role_id, '9540' FROM sys_role_menu WHERE menu_id = '9004';
