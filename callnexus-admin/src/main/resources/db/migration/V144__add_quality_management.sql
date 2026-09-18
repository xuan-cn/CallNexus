CREATE TABLE IF NOT EXISTS cc_quality_template (
    id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    template_code VARCHAR(32) NOT NULL,
    template_name VARCHAR(64) NOT NULL,
    direction_scope VARCHAR(16) NOT NULL DEFAULT 'ALL',
    total_score INT NOT NULL DEFAULT 100,
    qualified_score INT NOT NULL DEFAULT 80,
    ai_review_enabled TINYINT(1) NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    current_version_id BIGINT NULL,
    remark VARCHAR(500) NULL,
    create_dept BIGINT NULL, create_by BIGINT NULL, create_time DATETIME NULL,
    update_by BIGINT NULL, update_time DATETIME NULL,
    version INT NOT NULL DEFAULT 0, deleted TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_quality_template_code (tenant_id, template_code, deleted),
    KEY idx_quality_template_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质检评分模板';

CREATE TABLE IF NOT EXISTS cc_quality_template_version (
    id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    template_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED',
    template_snapshot_json LONGTEXT NOT NULL,
    published_at DATETIME NOT NULL,
    published_by BIGINT NULL,
    create_dept BIGINT NULL, create_by BIGINT NULL, create_time DATETIME NULL,
    update_by BIGINT NULL, update_time DATETIME NULL,
    version INT NOT NULL DEFAULT 0, deleted TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_quality_template_version (tenant_id, template_id, version_no, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质检模板版本';

CREATE TABLE IF NOT EXISTS cc_quality_template_item (
    id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    template_id BIGINT NOT NULL,
    template_version_id BIGINT NULL,
    dimension_code VARCHAR(32) NOT NULL,
    dimension_name VARCHAR(64) NOT NULL,
    item_code VARCHAR(64) NOT NULL,
    item_name VARCHAR(128) NOT NULL,
    item_type VARCHAR(20) NOT NULL DEFAULT 'DEDUCTION',
    score_value INT NOT NULL DEFAULT 0,
    fatal_flag TINYINT(1) NOT NULL DEFAULT 0,
    allow_not_applicable TINYINT(1) NOT NULL DEFAULT 0,
    evidence_required TINYINT(1) NOT NULL DEFAULT 0,
    rule_description VARCHAR(1000) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    create_dept BIGINT NULL, create_by BIGINT NULL, create_time DATETIME NULL,
    update_by BIGINT NULL, update_time DATETIME NULL,
    version INT NOT NULL DEFAULT 0, deleted TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_quality_template_item (tenant_id, template_id, template_version_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质检评分项';

CREATE TABLE IF NOT EXISTS cc_quality_task (
    id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    task_code VARCHAR(64) NOT NULL,
    source VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    call_session_id BIGINT NOT NULL,
    business_call_id VARCHAR(64) NULL,
    template_id BIGINT NOT NULL,
    template_version_id BIGINT NOT NULL,
    agent_id BIGINT NULL, agent_name VARCHAR(64) NULL, agent_extension VARCHAR(32) NULL,
    queue_id BIGINT NULL, queue_name VARCHAR(128) NULL,
    reviewer_id BIGINT NULL, reviewer_name VARCHAR(64) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    priority INT NOT NULL DEFAULT 5,
    assigned_at DATETIME NULL, submitted_at DATETIME NULL, published_at DATETIME NULL, closed_at DATETIME NULL,
    create_dept BIGINT NULL, create_by BIGINT NULL, create_time DATETIME NULL,
    update_by BIGINT NULL, update_time DATETIME NULL,
    version INT NOT NULL DEFAULT 0, deleted TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_quality_task_call_template (tenant_id, call_session_id, template_version_id, deleted),
    KEY idx_quality_task_status_reviewer (tenant_id, status, reviewer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质检任务';

CREATE TABLE IF NOT EXISTS cc_quality_result (
    id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    task_id BIGINT NOT NULL,
    result_version INT NOT NULL DEFAULT 1,
    total_score INT NOT NULL,
    qualified TINYINT(1) NOT NULL DEFAULT 0,
    fatal_flag TINYINT(1) NOT NULL DEFAULT 0,
    summary VARCHAR(2000) NULL,
    improvement_suggestion VARCHAR(2000) NULL,
    source VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    effective_flag TINYINT(1) NOT NULL DEFAULT 1,
    create_dept BIGINT NULL, create_by BIGINT NULL, create_time DATETIME NULL,
    update_by BIGINT NULL, update_time DATETIME NULL,
    version INT NOT NULL DEFAULT 0, deleted TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_quality_result_task (tenant_id, task_id, effective_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质检结果';

CREATE TABLE IF NOT EXISTS cc_quality_item_result (
    id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    quality_result_id BIGINT NOT NULL,
    template_item_id BIGINT NOT NULL,
    item_code VARCHAR(64) NOT NULL, item_name VARCHAR(128) NOT NULL,
    result VARCHAR(20) NOT NULL,
    score_change INT NOT NULL DEFAULT 0,
    reason VARCHAR(1000) NULL,
    evidence_json LONGTEXT NULL,
    manually_modified TINYINT(1) NOT NULL DEFAULT 1,
    create_dept BIGINT NULL, create_by BIGINT NULL, create_time DATETIME NULL,
    update_by BIGINT NULL, update_time DATETIME NULL,
    version INT NOT NULL DEFAULT 0, deleted TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_quality_item_result (tenant_id, quality_result_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质检评分项结果';

CREATE TABLE IF NOT EXISTS cc_quality_audit_log (
    id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    task_id BIGINT NOT NULL,
    operation_type VARCHAR(32) NOT NULL,
    before_json LONGTEXT NULL, after_json LONGTEXT NULL,
    operator_id BIGINT NULL, operator_name VARCHAR(64) NULL,
    operation_time DATETIME NOT NULL,
    remark VARCHAR(500) NULL,
    create_dept BIGINT NULL, create_by BIGINT NULL, create_time DATETIME NULL,
    update_by BIGINT NULL, update_time DATETIME NULL,
    version INT NOT NULL DEFAULT 0, deleted TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_quality_audit_task (tenant_id, task_id, operation_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质检审计日志';

SET @quality_root = COALESCE((SELECT menu_id FROM sys_menu WHERE parent_id = 0 AND path = 'quality-management' LIMIT 1), 2096800000000000000);
SET @quality_workbench = 2096800000000000001;
SET @quality_task = 2096800000000000002;
SET @quality_template = 2096800000000000003;

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT @quality_root, '质检管理', 0, 13, 'quality-management', NULL, '', 1, 0, 'M', '0', '0', '', 'education', 103, 1, SYSDATE(), NULL, NULL, '通话质量检查与评分'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = @quality_root OR (parent_id = 0 AND path = 'quality-management'));
INSERT INTO sys_menu VALUES (@quality_workbench, '质检工作台', @quality_root, 1, 'workbench', 'callcenter/quality-workbench/index', '', 1, 0, 'C', '0', '0', 'callcenter:quality-workbench:view', 'monitor', 103, 1, SYSDATE(), NULL, NULL, '')
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name), component = VALUES(component), perms = VALUES(perms);
INSERT INTO sys_menu VALUES (@quality_task, '质检任务', @quality_root, 2, 'tasks', 'callcenter/quality-task/index', '', 1, 0, 'C', '0', '0', 'callcenter:quality-task:list', 'list', 103, 1, SYSDATE(), NULL, NULL, '')
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name), component = VALUES(component), perms = VALUES(perms);
INSERT INTO sys_menu VALUES (@quality_template, '评分模板', @quality_root, 3, 'templates', 'callcenter/quality-template/index', '', 1, 0, 'C', '0', '0', 'callcenter:quality-template:list', 'form', 103, 1, SYSDATE(), NULL, NULL, '')
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name), component = VALUES(component), perms = VALUES(perms);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT permission_id, permission_name, parent_id, order_num, '', '', '', 1, 0, 'F', '0', '0', permission_code, '#', 103, 1, SYSDATE(), NULL, NULL, ''
FROM (
    SELECT 2096800000000000101 permission_id, '模板查询' permission_name, @quality_template parent_id, 1 order_num, 'callcenter:quality-template:query' permission_code UNION ALL
    SELECT 2096800000000000102, '模板新增', @quality_template, 2, 'callcenter:quality-template:create' UNION ALL
    SELECT 2096800000000000103, '模板修改', @quality_template, 3, 'callcenter:quality-template:update' UNION ALL
    SELECT 2096800000000000104, '模板删除', @quality_template, 4, 'callcenter:quality-template:delete' UNION ALL
    SELECT 2096800000000000105, '模板发布', @quality_template, 5, 'callcenter:quality-template:publish' UNION ALL
    SELECT 2096800000000000106, '任务查询', @quality_task, 1, 'callcenter:quality-task:query' UNION ALL
    SELECT 2096800000000000107, '任务创建', @quality_task, 2, 'callcenter:quality-task:create' UNION ALL
    SELECT 2096800000000000108, '任务分配', @quality_task, 3, 'callcenter:quality-task:assign' UNION ALL
    SELECT 2096800000000000109, '任务领取', @quality_workbench, 1, 'callcenter:quality-task:claim' UNION ALL
    SELECT 2096800000000000110, '质检提交', @quality_workbench, 2, 'callcenter:quality-task:submit' UNION ALL
    SELECT 2096800000000000111, '结果发布', @quality_task, 4, 'callcenter:quality-task:publish'
) permissions
WHERE NOT EXISTS (SELECT 1 FROM sys_menu existing WHERE existing.perms = permissions.permission_code);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role.role_id, menu.menu_id
FROM sys_role role
JOIN sys_menu menu ON menu.menu_id IN (@quality_root, @quality_workbench, @quality_task, @quality_template)
WHERE role.role_key IN ('admin', 'cc_supervisor', 'cc_qa');
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role.role_id, menu.menu_id
FROM sys_role role
JOIN sys_menu menu ON menu.perms LIKE 'callcenter:quality-%'
WHERE role.role_key IN ('admin', 'cc_supervisor', 'cc_qa');
