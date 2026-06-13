CREATE TABLE cc_ivr_flow (
    id BIGINT NOT NULL COMMENT 'IVR流程ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    flow_code VARCHAR(32) NOT NULL COMMENT '流程编码',
    flow_name VARCHAR(64) NOT NULL COMMENT '流程名称',
    node_group_id BIGINT NOT NULL COMMENT '目标FreeSWITCH节点组ID',
    draft_graph_json LONGTEXT NOT NULL COMMENT '草稿流程图JSON',
    latest_version_no INT NOT NULL DEFAULT 0 COMMENT '最新发布版本号',
    publish_status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT '发布状态：草稿、已发布',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    remark VARCHAR(500) NULL COMMENT '备注',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_ivr_flow_code (tenant_id, flow_code, deleted),
    KEY idx_cc_ivr_flow_group (tenant_id, node_group_id, enabled)
) ENGINE=InnoDB COMMENT='IVR流程';

CREATE TABLE cc_ivr_flow_version (
    id BIGINT NOT NULL COMMENT 'IVR流程版本ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    flow_id BIGINT NOT NULL COMMENT 'IVR流程ID',
    version_no INT NOT NULL COMMENT '版本号',
    graph_json LONGTEXT NOT NULL COMMENT '已发布流程图JSON',
    status VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED' COMMENT '版本状态：已发布、已停用',
    published_at DATETIME NOT NULL COMMENT '发布时间',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_ivr_flow_version (tenant_id, flow_id, version_no, deleted)
) ENGINE=InnoDB COMMENT='IVR不可变发布版本';

INSERT INTO sys_menu VALUES('9120', 'IVR流程', '9000', '12', 'ivr-flow', 'callcenter/ivr-flow/index', '', 1, 0, 'C', '0', '0', 'callcenter:ivr-flow:list', 'share', 103, 1, sysdate(), null, null, 'IVR流程管理菜单');
INSERT INTO sys_menu VALUES('9121', 'IVR查询', '9120', '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ivr-flow:query', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9122', 'IVR新增', '9120', '2', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ivr-flow:create', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9123', 'IVR修改', '9120', '3', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ivr-flow:update', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9124', 'IVR删除', '9120', '4', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ivr-flow:delete', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9125', 'IVR发布', '9120', '5', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ivr-flow:publish', '#', 103, 1, sysdate(), null, null, '');
