-- 客户资料任务管理新模型。旧导入批次不迁移，直接重建导入相关表。

DELETE FROM cc_customer_assignment WHERE assignment_source LIKE 'IMPORT%';
DROP TABLE IF EXISTS cc_customer_import_row;
DROP TABLE IF EXISTS cc_customer_import_batch;
DROP TABLE IF EXISTS cc_customer_import_task;

CREATE TABLE cc_customer_import_task (
    id BIGINT NOT NULL COMMENT '导入任务ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    task_code VARCHAR(64) NOT NULL COMMENT '任务编码',
    task_name VARCHAR(128) NOT NULL COMMENT '任务名称',
    description VARCHAR(500) NULL COMMENT '任务说明',
    status VARCHAR(32) NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED、DISABLED',
    duplicate_strategy VARCHAR(32) NOT NULL DEFAULT 'SKIP' COMMENT '重复号码策略：SKIP、UPDATE',
    form_template_id BIGINT NULL COMMENT '客户表单模板ID',
    field_mapping_json MEDIUMTEXT NULL COMMENT 'Excel字段映射JSON',
    default_customer_type VARCHAR(64) NULL COMMENT '默认客户类型',
    default_source_channel VARCHAR(64) NULL COMMENT '默认来源渠道',
    default_tags VARCHAR(500) NULL COMMENT '默认标签',
    default_remark VARCHAR(500) NULL COMMENT '默认备注',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_customer_import_task_code (tenant_id, task_code, deleted),
    KEY idx_cc_customer_import_task_status (tenant_id, status, create_time)
) ENGINE=InnoDB COMMENT='客户资料导入任务表';

CREATE TABLE cc_customer_import_batch (
    id BIGINT NOT NULL COMMENT '导入批次ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    task_id BIGINT NOT NULL COMMENT '导入任务ID',
    file_name VARCHAR(255) NOT NULL COMMENT '上传文件名',
    status VARCHAR(32) NOT NULL COMMENT '状态：PENDING、PROCESSING、SUCCESS、PARTIAL_SUCCESS、FAILED',
    duplicate_strategy VARCHAR(32) NOT NULL COMMENT '批次策略快照：SKIP、UPDATE',
    form_template_id BIGINT NULL COMMENT '表单模板快照',
    field_mapping_json MEDIUMTEXT NULL COMMENT '字段映射快照JSON',
    default_customer_type VARCHAR(64) NULL COMMENT '默认客户类型快照',
    default_source_channel VARCHAR(64) NULL COMMENT '默认来源渠道快照',
    default_tags VARCHAR(500) NULL COMMENT '默认标签快照',
    default_remark VARCHAR(500) NULL COMMENT '默认备注快照',
    total_count INT NOT NULL DEFAULT 0 COMMENT '总行数',
    imported_count INT NOT NULL DEFAULT 0 COMMENT '导入成功数',
    skipped_count INT NOT NULL DEFAULT 0 COMMENT '跳过数',
    failed_count INT NOT NULL DEFAULT 0 COMMENT '失败数',
    failure_reason VARCHAR(1000) NULL COMMENT '批次失败原因',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '上传人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    KEY idx_cc_customer_import_batch_task (tenant_id, task_id, create_time),
    KEY idx_cc_customer_import_batch_status (tenant_id, task_id, status)
) ENGINE=InnoDB COMMENT='客户资料上传批次表';

CREATE TABLE cc_customer_import_row (
    id BIGINT NOT NULL COMMENT '导入行ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    task_id BIGINT NOT NULL COMMENT '导入任务ID',
    batch_id BIGINT NOT NULL COMMENT '上传批次ID',
    source_row_number INT NOT NULL COMMENT 'Excel源行号',
    customer_name VARCHAR(128) NULL COMMENT '客户姓名',
    original_phone VARCHAR(64) NULL COMMENT '原始主号码',
    normalized_phone VARCHAR(64) NULL COMMENT '规范化主号码',
    additional_phones VARCHAR(1000) NULL COMMENT '附加号码原文',
    customer_type VARCHAR(64) NULL COMMENT '客户类型',
    source_channel VARCHAR(64) NULL COMMENT '来源渠道',
    tags VARCHAR(500) NULL COMMENT '客户标签',
    status VARCHAR(32) NOT NULL COMMENT '状态：IMPORTED、SKIPPED、FAILED',
    error_message VARCHAR(1000) NULL COMMENT '处理结果或失败原因',
    customer_id BIGINT NULL COMMENT '生成或关联的客户ID',
    raw_json MEDIUMTEXT NULL COMMENT '原始行JSON',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_customer_import_row (tenant_id, batch_id, source_row_number, deleted),
    KEY idx_cc_customer_import_row_task (tenant_id, task_id, status),
    KEY idx_cc_customer_import_row_batch (tenant_id, batch_id, status),
    KEY idx_cc_customer_import_row_customer (tenant_id, customer_id)
) ENGINE=InnoDB COMMENT='客户资料导入行明细表';

-- 删除旧客户管理目录下的资料分配入口和旧导入权限。
DELETE rm FROM sys_role_menu rm INNER JOIN sys_menu m ON m.menu_id = rm.menu_id
WHERE m.menu_id IN ('9540', '9493') OR m.perms = 'callcenter:customer:import';
DELETE FROM sys_menu WHERE menu_id IN ('9540', '9493') OR perms = 'callcenter:customer:import';

-- 新增一级任务管理目录及页面。
INSERT INTO sys_menu VALUES(
    '9600', '任务管理', '0', '18', 'task-management', '', '', 1, 0, 'M', '0', '0', '', 'task', 103, 1,
    SYSDATE(), NULL, NULL, '客户资料导入与分配任务管理'
);
INSERT INTO sys_menu VALUES(
    '9601', '资料导入', '9600', '1', 'customer-import-task', 'callcenter/customer-import-task/index', '', 1, 0, 'C', '0', '0',
    'callcenter:customer-import-task:list', 'upload', 103, 1, SYSDATE(), NULL, NULL, '资料导入任务、文件批次和失败明细'
);
INSERT INTO sys_menu VALUES(
    '9602', '资料分配', '9600', '2', 'customer-assignment', 'callcenter/customer-assignment/index', '', 1, 0, 'C', '0', '0',
    'callcenter:customer-assignment:list', 'peoples', 103, 1, SYSDATE(), NULL, NULL, '按导入任务分配客户资料'
);

INSERT INTO sys_menu VALUES('9610', '导入任务查询', '9601', '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:customer-import-task:list', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT INTO sys_menu VALUES('9611', '导入任务新增', '9601', '2', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:customer-import-task:create', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT INTO sys_menu VALUES('9612', '导入任务修改', '9601', '3', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:customer-import-task:edit', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT INTO sys_menu VALUES('9613', '导入任务删除', '9601', '4', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:customer-import-task:delete', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT INTO sys_menu VALUES('9614', '导入文件上传', '9601', '5', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:customer-import-task:upload', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT INTO sys_menu VALUES('9615', '导入失败重试', '9601', '6', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:customer-import-task:retry', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT INTO sys_menu VALUES('9620', '资料分配查询', '9602', '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:customer-assignment:list', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT INTO sys_menu VALUES('9621', '客户资料分配', '9602', '2', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:customer-assignment:assign', '#', 103, 1, SYSDATE(), NULL, NULL, '');

-- 继承原客户管理目录的角色授权。
INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
SELECT DISTINCT role_id, '9600' FROM sys_role_menu WHERE menu_id = '9004';
INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
SELECT DISTINCT role_id, '9601' FROM sys_role_menu WHERE menu_id = '9004';
INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
SELECT DISTINCT role_id, '9602' FROM sys_role_menu WHERE menu_id = '9004';
INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
SELECT DISTINCT role_id, '9610' FROM sys_role_menu WHERE menu_id = '9004';
INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
SELECT DISTINCT role_id, '9611' FROM sys_role_menu WHERE menu_id = '9004';
INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
SELECT DISTINCT role_id, '9612' FROM sys_role_menu WHERE menu_id = '9004';
INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
SELECT DISTINCT role_id, '9613' FROM sys_role_menu WHERE menu_id = '9004';
INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
SELECT DISTINCT role_id, '9614' FROM sys_role_menu WHERE menu_id = '9004';
INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
SELECT DISTINCT role_id, '9615' FROM sys_role_menu WHERE menu_id = '9004';
INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
SELECT DISTINCT role_id, '9620' FROM sys_role_menu WHERE menu_id = '9004';
INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
SELECT DISTINCT role_id, '9621' FROM sys_role_menu WHERE menu_id = '9004';
