-- 区号维护菜单与权限。
INSERT IGNORE INTO sys_menu VALUES('9470', '区号维护', '9000', '70', 'area-code', 'callcenter/area-code/index', '', 1, 0, 'C', '0', '0', 'callcenter:area-code:list', 'guide', 103, 1, sysdate(), null, null, '维护固话区号，提供号码规范化测试');
INSERT IGNORE INTO sys_menu VALUES('9471', '区号查询', '9470', '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:area-code:query', '#', 103, 1, sysdate(), null, null, '');
INSERT IGNORE INTO sys_menu VALUES('9472', '区号新增', '9470', '2', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:area-code:create', '#', 103, 1, sysdate(), null, null, '');
INSERT IGNORE INTO sys_menu VALUES('9473', '区号修改', '9470', '3', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:area-code:update', '#', 103, 1, sysdate(), null, null, '');
INSERT IGNORE INTO sys_menu VALUES('9474', '区号删除', '9470', '4', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:area-code:delete', '#', 103, 1, sysdate(), null, null, '');
