CREATE TABLE IF NOT EXISTS cc_quality_sampling_plan (
    id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    plan_code VARCHAR(32) NOT NULL,
    plan_name VARCHAR(64) NOT NULL,
    template_id BIGINT NOT NULL,
    sampling_method VARCHAR(24) NOT NULL DEFAULT 'RANDOM_COUNT',
    sample_count INT NULL,
    sample_rate DECIMAL(6,2) NULL,
    stratify_dimension VARCHAR(20) NULL,
    direction_scope VARCHAR(16) NOT NULL DEFAULT 'ALL',
    queue_id BIGINT NULL,
    skill_group_id BIGINT NULL,
    agent_id BIGINT NULL,
    min_duration_seconds INT NULL,
    max_duration_seconds INT NULL,
    require_recording TINYINT(1) NULL,
    require_transcript TINYINT(1) NULL,
    lookback_days INT NOT NULL DEFAULT 1,
    min_per_agent INT NOT NULL DEFAULT 0,
    max_per_agent INT NULL,
    cooldown_days INT NOT NULL DEFAULT 30,
    exclude_sampled TINYINT(1) NOT NULL DEFAULT 1,
    reviewer_id BIGINT NULL,
    priority INT NOT NULL DEFAULT 5,
    schedule_type VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    schedule_time TIME NULL,
    schedule_day INT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    last_executed_at DATETIME NULL,
    remark VARCHAR(500) NULL,
    create_dept BIGINT NULL, create_by BIGINT NULL, create_time DATETIME NULL,
    update_by BIGINT NULL, update_time DATETIME NULL,
    version INT NOT NULL DEFAULT 0, deleted TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_quality_sampling_plan_code (tenant_id, plan_code, deleted),
    KEY idx_quality_sampling_plan_schedule (tenant_id, enabled, schedule_type, last_executed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质检抽检计划';

CREATE TABLE IF NOT EXISTS cc_quality_sampling_execution (
    id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    plan_id BIGINT NOT NULL,
    execution_code VARCHAR(64) NOT NULL,
    trigger_type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    window_start DATETIME NULL,
    window_end DATETIME NULL,
    candidate_count INT NOT NULL DEFAULT 0,
    selected_count INT NOT NULL DEFAULT 0,
    task_count INT NOT NULL DEFAULT 0,
    excluded_count INT NOT NULL DEFAULT 0,
    exclusion_summary VARCHAR(1000) NULL,
    error_message VARCHAR(2000) NULL,
    started_at DATETIME NOT NULL,
    finished_at DATETIME NULL,
    create_dept BIGINT NULL, create_by BIGINT NULL, create_time DATETIME NULL,
    update_by BIGINT NULL, update_time DATETIME NULL,
    version INT NOT NULL DEFAULT 0, deleted TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_quality_sampling_execution_code (tenant_id, execution_code),
    KEY idx_quality_sampling_execution_plan (tenant_id, plan_id, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质检抽检执行记录';

ALTER TABLE cc_quality_task
    ADD COLUMN sampling_plan_id BIGINT NULL AFTER source,
    ADD COLUMN sampling_execution_id BIGINT NULL AFTER sampling_plan_id,
    ADD KEY idx_quality_task_sampling_execution (tenant_id, sampling_execution_id);

SET @quality_root = (SELECT menu_id FROM sys_menu WHERE parent_id = 0 AND path = 'quality-management' LIMIT 1);
SET @quality_plan = 2096800000000000004;

INSERT INTO sys_menu VALUES (@quality_plan, '抽检计划', @quality_root, 4, 'sampling-plans', 'callcenter/quality-sampling-plan/index', '', 1, 0, 'C', '0', '0', 'callcenter:quality-plan:list', 'calendar', 103, 1, SYSDATE(), NULL, NULL, '')
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name), component = VALUES(component), perms = VALUES(perms);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT permission_id, permission_name, @quality_plan, order_num, '', '', '', 1, 0, 'F', '0', '0', permission_code, '#', 103, 1, SYSDATE(), NULL, NULL, ''
FROM (
    SELECT 2096800000000000112 permission_id, '计划查询' permission_name, 1 order_num, 'callcenter:quality-plan:query' permission_code UNION ALL
    SELECT 2096800000000000113, '计划新增', 2, 'callcenter:quality-plan:create' UNION ALL
    SELECT 2096800000000000114, '计划修改', 3, 'callcenter:quality-plan:update' UNION ALL
    SELECT 2096800000000000115, '计划删除', 4, 'callcenter:quality-plan:delete' UNION ALL
    SELECT 2096800000000000116, '计划执行', 5, 'callcenter:quality-plan:execute'
) permissions
WHERE NOT EXISTS (SELECT 1 FROM sys_menu existing WHERE existing.perms = permissions.permission_code);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role.role_id, @quality_plan FROM sys_role role
WHERE role.role_key IN ('admin', 'cc_supervisor', 'cc_qa');
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role.role_id, menu.menu_id FROM sys_role role
JOIN sys_menu menu ON menu.perms LIKE 'callcenter:quality-plan:%'
WHERE role.role_key IN ('admin', 'cc_supervisor', 'cc_qa');
