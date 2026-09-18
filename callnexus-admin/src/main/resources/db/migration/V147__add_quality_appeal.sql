CREATE TABLE IF NOT EXISTS cc_quality_appeal (
    id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    task_id BIGINT NOT NULL,
    quality_result_id BIGINT NOT NULL,
    appeal_reason VARCHAR(2000) NOT NULL,
    attachment_oss_ids VARCHAR(2000) NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'SUBMITTED',
    appellant_id BIGINT NOT NULL,
    appellant_name VARCHAR(64) NULL,
    appealed_at DATETIME NOT NULL,
    appeal_deadline DATETIME NOT NULL,
    reviewer_id BIGINT NULL,
    reviewer_name VARCHAR(64) NULL,
    reviewed_at DATETIME NULL,
    review_conclusion VARCHAR(2000) NULL,
    review_result_id BIGINT NULL,
    create_dept BIGINT NULL, create_by BIGINT NULL, create_time DATETIME NULL,
    update_by BIGINT NULL, update_time DATETIME NULL,
    version INT NOT NULL DEFAULT 0, deleted TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_quality_appeal_task (tenant_id, task_id, appealed_at),
    KEY idx_quality_appeal_status (tenant_id, status, appealed_at),
    KEY idx_quality_appeal_appellant (tenant_id, appellant_id, appealed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质检结果申诉复核';

SET @quality_root = (SELECT menu_id FROM sys_menu WHERE parent_id = 0 AND path = 'quality-management' LIMIT 1);
SET @quality_my_result = 2096800000000000005;
SET @quality_appeal = 2096800000000000006;

INSERT INTO sys_menu VALUES (@quality_my_result, '我的质检', @quality_root, 5, 'my-results', 'callcenter/quality-my-result/index', '', 1, 0, 'C', '0', '0', 'callcenter:quality-result:mine', 'user', 103, 1, SYSDATE(), NULL, NULL, '')
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name), component = VALUES(component), perms = VALUES(perms);
INSERT INTO sys_menu VALUES (@quality_appeal, '申诉复核', @quality_root, 6, 'appeals', 'callcenter/quality-appeal/index', '', 1, 0, 'C', '0', '0', 'callcenter:quality-appeal:list', 'audit', 103, 1, SYSDATE(), NULL, NULL, '')
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name), component = VALUES(component), perms = VALUES(perms);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT permission_id, permission_name, parent_id, order_num, '', '', '', 1, 0, 'F', '0', '0', permission_code, '#', 103, 1, SYSDATE(), NULL, NULL, ''
FROM (
    SELECT 2096800000000000118 permission_id, '本人结果查询' permission_name, @quality_my_result parent_id, 1 order_num, 'callcenter:quality-result:mine' permission_code UNION ALL
    SELECT 2096800000000000119, '提交申诉', @quality_my_result, 2, 'callcenter:quality-appeal:create' UNION ALL
    SELECT 2096800000000000120, '申诉查询', @quality_appeal, 1, 'callcenter:quality-appeal:query' UNION ALL
    SELECT 2096800000000000121, '申诉复核', @quality_appeal, 2, 'callcenter:quality-appeal:review'
) permissions
WHERE NOT EXISTS (SELECT 1 FROM sys_menu existing WHERE existing.perms = permissions.permission_code);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role.role_id, menu.menu_id FROM sys_role role
JOIN sys_menu menu ON menu.menu_id IN (@quality_root, @quality_my_result)
WHERE role.role_key = 'cc_agent';
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role.role_id, menu.menu_id FROM sys_role role
JOIN sys_menu menu ON menu.perms IN ('callcenter:quality-result:mine', 'callcenter:quality-appeal:create')
WHERE role.role_key = 'cc_agent';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role.role_id, menu.menu_id FROM sys_role role
JOIN sys_menu menu ON menu.menu_id IN (@quality_root, @quality_appeal)
WHERE role.role_key IN ('admin', 'cc_supervisor', 'cc_qa');
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role.role_id, menu.menu_id FROM sys_role role
JOIN sys_menu menu ON menu.perms IN ('callcenter:quality-appeal:query', 'callcenter:quality-appeal:review')
WHERE role.role_key IN ('admin', 'cc_supervisor', 'cc_qa');
