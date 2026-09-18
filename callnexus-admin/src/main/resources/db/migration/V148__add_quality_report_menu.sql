SET @quality_root = (SELECT menu_id FROM sys_menu WHERE parent_id = 0 AND path = 'quality-management' LIMIT 1);
SET @quality_report = 2096800000000000007;

INSERT INTO sys_menu VALUES (@quality_report, '质检报表', @quality_root, 7, 'reports', 'callcenter/quality-report/index', '', 1, 0, 'C', '0', '0', 'callcenter:report-quality:view', 'chart', 103, 1, SYSDATE(), NULL, NULL, '')
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name), component = VALUES(component), perms = VALUES(perms), icon = VALUES(icon);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role.role_id, @quality_report
FROM sys_role role
WHERE role.role_key IN ('admin', 'cc_supervisor', 'cc_qa');
