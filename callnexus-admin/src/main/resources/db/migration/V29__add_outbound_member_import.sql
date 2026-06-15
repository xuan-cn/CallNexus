ALTER TABLE cc_outbound_member
    ADD COLUMN source_type VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT '名单来源：MANUAL手工选择、EXCEL表格导入' AFTER phone_number,
    ADD COLUMN import_batch_id BIGINT NULL COMMENT '表格导入批次ID' AFTER source_type,
    ADD KEY idx_cc_outbound_member_import_batch (tenant_id, import_batch_id);

CREATE TABLE cc_outbound_import_batch (
    id BIGINT NOT NULL COMMENT '外呼名单导入批次ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    task_id BIGINT NOT NULL COMMENT '外呼任务ID',
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
    KEY idx_cc_outbound_import_batch_task (tenant_id, task_id, create_time)
) ENGINE=InnoDB COMMENT='外呼名单导入批次';

CREATE TABLE cc_outbound_import_row (
    id BIGINT NOT NULL COMMENT '外呼名单导入明细ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    batch_id BIGINT NOT NULL COMMENT '导入批次ID',
    source_row_number INT NOT NULL COMMENT '原始表格行号',
    customer_name VARCHAR(128) NULL COMMENT '客户姓名',
    original_phone VARCHAR(64) NULL COMMENT '原始电话号码',
    normalized_phone VARCHAR(32) NULL COMMENT '清洗后的电话号码',
    status VARCHAR(32) NOT NULL COMMENT '预检状态：VALID有效、INVALID无效、DUPLICATE_FILE文件内重复、DUPLICATE_TASK任务内重复',
    error_message VARCHAR(255) NULL COMMENT '预检失败原因',
    customer_id BIGINT NULL COMMENT '已匹配或创建的客户ID',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    KEY idx_cc_outbound_import_row_batch (tenant_id, batch_id, source_row_number),
    KEY idx_cc_outbound_import_row_phone (tenant_id, normalized_phone)
) ENGINE=InnoDB COMMENT='外呼名单导入预检明细';
