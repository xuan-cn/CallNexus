CREATE TABLE IF NOT EXISTS cc_quality_report_snapshot (
    id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    period_type VARCHAR(16) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    scope_type VARCHAR(20) NOT NULL DEFAULT 'TENANT',
    scope_id BIGINT NOT NULL DEFAULT 0,
    scope_name VARCHAR(128) NOT NULL,
    eligible_call_count BIGINT NOT NULL DEFAULT 0,
    reviewed_call_count BIGINT NOT NULL DEFAULT 0,
    coverage_rate DECIMAL(8,2) NOT NULL DEFAULT 0,
    result_count BIGINT NOT NULL DEFAULT 0,
    average_score DECIMAL(8,2) NOT NULL DEFAULT 0,
    qualified_count BIGINT NOT NULL DEFAULT 0,
    qualified_rate DECIMAL(8,2) NOT NULL DEFAULT 0,
    fatal_count BIGINT NOT NULL DEFAULT 0,
    fatal_rate DECIMAL(8,2) NOT NULL DEFAULT 0,
    ai_adoption_rate DECIMAL(8,2) NOT NULL DEFAULT 0,
    appeal_rate DECIMAL(8,2) NOT NULL DEFAULT 0,
    generated_at DATETIME NOT NULL,
    create_dept BIGINT NULL, create_by BIGINT NULL, create_time DATETIME NULL,
    update_by BIGINT NULL, update_time DATETIME NULL,
    version INT NOT NULL DEFAULT 0, deleted TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_quality_snapshot_period (tenant_id, period_type, period_start, scope_type, scope_id, deleted),
    KEY idx_quality_snapshot_time (tenant_id, generated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质检日报周报快照';

CREATE TABLE IF NOT EXISTS cc_quality_alert_rule (
    id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    rule_name VARCHAR(128) NOT NULL,
    period_type VARCHAR(16) NOT NULL,
    metric_code VARCHAR(32) NOT NULL,
    compare_operator VARCHAR(8) NOT NULL,
    threshold_value DECIMAL(10,2) NOT NULL,
    scope_type VARCHAR(20) NOT NULL DEFAULT 'TENANT',
    scope_id BIGINT NOT NULL DEFAULT 0,
    scope_name VARCHAR(128) NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    remark VARCHAR(500) NULL,
    create_dept BIGINT NULL, create_by BIGINT NULL, create_time DATETIME NULL,
    update_by BIGINT NULL, update_time DATETIME NULL,
    version INT NOT NULL DEFAULT 0, deleted TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_quality_alert_rule_enabled (tenant_id, enabled, period_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质检风险预警规则';

CREATE TABLE IF NOT EXISTS cc_quality_alert_record (
    id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    rule_id BIGINT NOT NULL,
    snapshot_id BIGINT NOT NULL,
    rule_name VARCHAR(128) NOT NULL,
    period_type VARCHAR(16) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    metric_code VARCHAR(32) NOT NULL,
    actual_value DECIMAL(10,2) NOT NULL,
    compare_operator VARCHAR(8) NOT NULL,
    threshold_value DECIMAL(10,2) NOT NULL,
    scope_type VARCHAR(20) NOT NULL,
    scope_id BIGINT NOT NULL DEFAULT 0,
    scope_name VARCHAR(128) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    triggered_at DATETIME NOT NULL,
    acknowledged_by BIGINT NULL,
    acknowledged_name VARCHAR(64) NULL,
    acknowledged_at DATETIME NULL,
    closed_by BIGINT NULL,
    closed_name VARCHAR(64) NULL,
    closed_at DATETIME NULL,
    handle_remark VARCHAR(500) NULL,
    create_dept BIGINT NULL, create_by BIGINT NULL, create_time DATETIME NULL,
    update_by BIGINT NULL, update_time DATETIME NULL,
    version INT NOT NULL DEFAULT 0, deleted TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_quality_alert_snapshot (tenant_id, rule_id, snapshot_id, deleted),
    KEY idx_quality_alert_status (tenant_id, status, triggered_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质检风险预警记录';

SET @quality_root = (SELECT menu_id FROM sys_menu WHERE parent_id = 0 AND path = 'quality-management' LIMIT 1);
SET @quality_operation = 2096800000000000008;

INSERT INTO sys_menu VALUES (@quality_operation, '质检运营', @quality_root, 8, 'operations', 'callcenter/quality-operation/index', '', 1, 0, 'C', '0', '0', 'callcenter:quality-operation:view', 'warning', 103, 1, SYSDATE(), NULL, NULL, '')
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name), component = VALUES(component), perms = VALUES(perms), icon = VALUES(icon);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT permission_id, permission_name, @quality_operation, order_num, '', '', '', 1, 0, 'F', '0', '0', permission_code, '#', 103, 1, SYSDATE(), NULL, NULL, ''
FROM (
    SELECT 2096800000000000122 permission_id, '预警规则配置' permission_name, 1 order_num, 'callcenter:quality-operation:config' permission_code UNION ALL
    SELECT 2096800000000000123, '生成周期报表', 2, 'callcenter:quality-operation:generate' UNION ALL
    SELECT 2096800000000000124, '处理质检预警', 3, 'callcenter:quality-operation:handle'
) permissions
WHERE NOT EXISTS (SELECT 1 FROM sys_menu existing WHERE existing.perms = permissions.permission_code);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role.role_id, menu.menu_id FROM sys_role role
JOIN sys_menu menu ON menu.menu_id = @quality_operation OR menu.parent_id = @quality_operation
WHERE role.role_key IN ('admin', 'cc_supervisor', 'cc_qa');
