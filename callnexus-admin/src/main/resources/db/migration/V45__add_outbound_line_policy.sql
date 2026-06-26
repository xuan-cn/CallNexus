CREATE TABLE cc_outbound_line_policy (
    id BIGINT NOT NULL COMMENT '外呼线路策略ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    node_id BIGINT NOT NULL COMMENT 'FreeSWITCH节点ID',
    policy_code VARCHAR(64) NOT NULL COMMENT '策略编码',
    policy_name VARCHAR(128) NOT NULL COMMENT '策略名称',
    policy_type VARCHAR(32) NOT NULL COMMENT '策略类型：FIXED固定、ROUND_ROBIN轮询、WEIGHT权重',
    default_policy TINYINT NOT NULL DEFAULT 0 COMMENT '是否默认策略',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    remark VARCHAR(500) NULL COMMENT '备注',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_outbound_line_policy_code (tenant_id, policy_code, deleted),
    KEY idx_cc_outbound_line_policy_node (tenant_id, node_id, enabled),
    KEY idx_cc_outbound_line_policy_default (tenant_id, node_id, default_policy, enabled)
) ENGINE=InnoDB COMMENT='外呼线路策略';

CREATE TABLE cc_outbound_line_policy_item (
    id BIGINT NOT NULL COMMENT '外呼线路策略明细ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    policy_id BIGINT NOT NULL COMMENT '外呼线路策略ID',
    phone_number_id BIGINT NOT NULL COMMENT '外呼主叫号码ID',
    weight INT NOT NULL DEFAULT 1 COMMENT '权重，权重策略使用',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序，固定策略和兜底顺序使用',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    KEY idx_cc_outbound_line_policy_item_number (tenant_id, policy_id, phone_number_id),
    KEY idx_cc_outbound_line_policy_item_policy (tenant_id, policy_id, enabled, sort_order)
) ENGINE=InnoDB COMMENT='外呼线路策略明细';

INSERT IGNORE INTO sys_menu VALUES('9290', '外呼线路策略', '9202', '8', 'outbound-line-policy', 'callcenter/outbound-line-policy/index', '', 1, 0, 'C', '0', '0', 'callcenter:outbound-line-policy:list', 'guide', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9291', '外呼线路策略查询', '9290', '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:outbound-line-policy:query', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9292', '外呼线路策略新增', '9290', '2', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:outbound-line-policy:create', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9293', '外呼线路策略修改', '9290', '3', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:outbound-line-policy:update', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9294', '外呼线路策略删除', '9290', '4', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:outbound-line-policy:delete', '#', 103, 1, SYSDATE(), NULL, NULL, '');
