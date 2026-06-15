CREATE TABLE cc_outbound_blacklist (
    id BIGINT NOT NULL COMMENT '外呼黑名单ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    scope_type VARCHAR(16) NOT NULL COMMENT '限制范围：GLOBAL租户全局、TASK外呼任务',
    task_id BIGINT NULL COMMENT '外呼任务ID，全局黑名单为空',
    original_phone VARCHAR(64) NOT NULL COMMENT '原始电话号码',
    normalized_phone VARCHAR(32) NOT NULL COMMENT '标准化电话号码',
    reason VARCHAR(255) NULL COMMENT '拦截原因',
    source VARCHAR(32) NOT NULL DEFAULT 'MANUAL' COMMENT '来源：MANUAL手工、EXCEL表格、CUSTOMER_REQUEST客户要求、SYSTEM_RULE系统规则',
    effective_at DATETIME NULL COMMENT '生效时间，为空表示立即生效',
    expires_at DATETIME NULL COMMENT '失效时间，为空表示长期有效',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    active_unique_key VARCHAR(180) GENERATED ALWAYS AS (
        CASE WHEN deleted = 0 THEN CONCAT(scope_type, ':', IFNULL(CAST(task_id AS CHAR), 'GLOBAL'), ':', normalized_phone) ELSE NULL END
    ) STORED COMMENT '未删除记录唯一键',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_outbound_blacklist_active (tenant_id, active_unique_key),
    KEY idx_cc_outbound_blacklist_phone (tenant_id, normalized_phone, enabled),
    KEY idx_cc_outbound_blacklist_task (tenant_id, task_id, enabled),
    KEY idx_cc_outbound_blacklist_expiry (tenant_id, enabled, expires_at)
) ENGINE=InnoDB COMMENT='外呼黑名单';

CREATE TABLE cc_outbound_blacklist_import_batch (
    id BIGINT NOT NULL COMMENT '黑名单导入批次ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    scope_type VARCHAR(16) NOT NULL COMMENT '限制范围：GLOBAL租户全局、TASK外呼任务',
    task_id BIGINT NULL COMMENT '外呼任务ID',
    file_name VARCHAR(255) NOT NULL COMMENT '原始文件名称',
    status VARCHAR(16) NOT NULL DEFAULT 'PREVIEW' COMMENT '批次状态：PREVIEW待确认、IMPORTING导入中、IMPORTED已导入',
    total_count INT NOT NULL DEFAULT 0 COMMENT '总行数',
    valid_count INT NOT NULL DEFAULT 0 COMMENT '有效行数',
    invalid_count INT NOT NULL DEFAULT 0 COMMENT '无效行数',
    duplicate_count INT NOT NULL DEFAULT 0 COMMENT '重复行数',
    imported_count INT NOT NULL DEFAULT 0 COMMENT '实际导入行数',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    KEY idx_cc_outbound_blacklist_import_batch (tenant_id, create_time)
) ENGINE=InnoDB COMMENT='外呼黑名单导入批次';

CREATE TABLE cc_outbound_blacklist_import_row (
    id BIGINT NOT NULL COMMENT '黑名单导入明细ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    batch_id BIGINT NOT NULL COMMENT '导入批次ID',
    source_row_number INT NOT NULL COMMENT '原始表格行号',
    original_phone VARCHAR(64) NULL COMMENT '原始电话号码',
    normalized_phone VARCHAR(32) NULL COMMENT '标准化电话号码',
    reason VARCHAR(255) NULL COMMENT '拦截原因',
    status VARCHAR(32) NOT NULL COMMENT '预检状态：VALID有效、INVALID无效、DUPLICATE_FILE文件内重复、DUPLICATE_EXISTING已存在',
    error_message VARCHAR(255) NULL COMMENT '预检失败原因',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    KEY idx_cc_outbound_blacklist_import_row_batch (tenant_id, batch_id, source_row_number)
) ENGINE=InnoDB COMMENT='外呼黑名单导入预检明细';

ALTER TABLE cc_outbound_member
    ADD COLUMN blocked_reason VARCHAR(255) NULL COMMENT '黑名单拦截原因' AFTER completion_reason,
    ADD COLUMN blocked_at DATETIME NULL COMMENT '黑名单拦截时间' AFTER blocked_reason,
    ADD COLUMN blocked_blacklist_id BIGINT NULL COMMENT '命中的黑名单ID' AFTER blocked_at,
    ADD COLUMN status_before_blocked VARCHAR(16) NULL COMMENT '拦截前名单状态' AFTER blocked_blacklist_id,
    ADD KEY idx_cc_outbound_member_blocked (tenant_id, blocked_blacklist_id, status);

INSERT INTO sys_menu VALUES('9160', '外呼黑名单', '9200', '7', 'outbound-blacklist', 'callcenter/outbound-blacklist/index', '', 1, 0, 'C', '0', '0', 'callcenter:outbound-blacklist:list', 'lock', 103, 1, SYSDATE(), NULL, NULL, '外呼黑名单管理');
INSERT INTO sys_menu VALUES('9161', '黑名单查询', '9160', '1', '#', '', '', 1, 0, 'F', '0', '0', 'callcenter:outbound-blacklist:query', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT INTO sys_menu VALUES('9162', '黑名单新增', '9160', '2', '#', '', '', 1, 0, 'F', '0', '0', 'callcenter:outbound-blacklist:create', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT INTO sys_menu VALUES('9163', '黑名单修改', '9160', '3', '#', '', '', 1, 0, 'F', '0', '0', 'callcenter:outbound-blacklist:update', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT INTO sys_menu VALUES('9164', '黑名单解除', '9160', '4', '#', '', '', 1, 0, 'F', '0', '0', 'callcenter:outbound-blacklist:delete', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT INTO sys_menu VALUES('9165', '黑名单导入', '9160', '5', '#', '', '', 1, 0, 'F', '0', '0', 'callcenter:outbound-blacklist:import', '#', 103, 1, SYSDATE(), NULL, NULL, '');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role_id, '9160' FROM sys_role_menu WHERE menu_id = '9150';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role_id, permission_menu.menu_id
FROM sys_role_menu task_role
CROSS JOIN (
    SELECT '9161' AS menu_id UNION ALL SELECT '9162' UNION ALL SELECT '9163' UNION ALL SELECT '9164' UNION ALL SELECT '9165'
) permission_menu
WHERE task_role.menu_id = '9150';
