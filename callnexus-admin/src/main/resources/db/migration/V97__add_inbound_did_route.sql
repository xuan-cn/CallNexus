CREATE TABLE IF NOT EXISTS cc_inbound_did_entry (
    id BIGINT NOT NULL COMMENT 'DID/端口入口ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    node_id BIGINT NOT NULL COMMENT 'FreeSWITCH节点ID',
    gateway_id BIGINT NOT NULL COMMENT '来源网关ID',
    entry_name VARCHAR(64) NOT NULL COMMENT '入口名称，例如售前热线、SIM1、FXO端口1',
    entry_type VARCHAR(32) NOT NULL COMMENT '入口类型：DID、PORT、ACCOUNT、HEADER',
    did_number VARCHAR(64) NULL COMMENT 'DID号码或被叫号码',
    port_code VARCHAR(64) NULL COMMENT '端口标识，例如port1、line1、sim1',
    account_code VARCHAR(64) NULL COMMENT '账号标识，例如网关注账号',
    header_name VARCHAR(128) NULL COMMENT 'SIP Header名称',
    header_value VARCHAR(255) NULL COMMENT 'SIP Header值',
    route_target_type VARCHAR(32) NOT NULL COMMENT '路由目标类型：IVR、QUEUE、EXTENSION',
    route_target_id VARCHAR(64) NOT NULL COMMENT '路由目标ID或分机号',
    priority INT NOT NULL DEFAULT 100 COMMENT '匹配优先级，数值越小越优先',
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
    KEY idx_cc_inbound_did_entry_node_gateway (tenant_id, node_id, gateway_id, enabled),
    KEY idx_cc_inbound_did_entry_did (tenant_id, node_id, gateway_id, entry_type, did_number, deleted),
    KEY idx_cc_inbound_did_entry_port (tenant_id, node_id, gateway_id, entry_type, port_code, deleted),
    KEY idx_cc_inbound_did_entry_account (tenant_id, node_id, gateway_id, entry_type, account_code, deleted),
    KEY idx_cc_inbound_did_entry_header (tenant_id, node_id, gateway_id, entry_type, header_name, header_value, deleted)
) ENGINE=InnoDB COMMENT='呼入DID与端口入口表';

ALTER TABLE cc_call_record
    ADD COLUMN did_number VARCHAR(64) NULL COMMENT '命中的DID或入口号码' AFTER called_number,
    ADD COLUMN did_name VARCHAR(64) NULL COMMENT '命中的DID入口名称' AFTER did_number,
    ADD COLUMN gateway_id BIGINT NULL COMMENT '来源网关ID' AFTER did_name,
    ADD COLUMN inbound_route_id BIGINT NULL COMMENT '命中的呼入DID入口ID' AFTER gateway_id,
    ADD COLUMN inbound_route_target_type VARCHAR(32) NULL COMMENT '呼入路由目标类型' AFTER inbound_route_id,
    ADD COLUMN inbound_route_target_name VARCHAR(128) NULL COMMENT '呼入路由目标名称' AFTER inbound_route_target_type;

INSERT IGNORE INTO sys_menu VALUES(
    '9550', 'DID入口管理', '9000', '28', 'inbound-did', 'callcenter/inbound-did/index', '', 1, 0, 'C', '0', '0',
    'callcenter:inbound-did:list', 'connection', 103, 1, SYSDATE(), NULL, NULL, 'DID号码、端口和账号入口路由管理'
);
INSERT IGNORE INTO sys_menu VALUES(
    '9551', 'DID入口查询', '9550', '1', '', '', '', 1, 0, 'F', '0', '0',
    'callcenter:inbound-did:query', '#', 103, 1, SYSDATE(), NULL, NULL, ''
);
INSERT IGNORE INTO sys_menu VALUES(
    '9552', 'DID入口新增', '9550', '2', '', '', '', 1, 0, 'F', '0', '0',
    'callcenter:inbound-did:create', '#', 103, 1, SYSDATE(), NULL, NULL, ''
);
INSERT IGNORE INTO sys_menu VALUES(
    '9553', 'DID入口修改', '9550', '3', '', '', '', 1, 0, 'F', '0', '0',
    'callcenter:inbound-did:update', '#', 103, 1, SYSDATE(), NULL, NULL, ''
);
INSERT IGNORE INTO sys_menu VALUES(
    '9554', 'DID入口删除', '9550', '4', '', '', '', 1, 0, 'F', '0', '0',
    'callcenter:inbound-did:delete', '#', 103, 1, SYSDATE(), NULL, NULL, ''
);

INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
SELECT DISTINCT role_id, '9550' FROM sys_role_menu WHERE menu_id = '9000';
INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
SELECT DISTINCT role_id, '9551' FROM sys_role_menu WHERE menu_id = '9000';
INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
SELECT DISTINCT role_id, '9552' FROM sys_role_menu WHERE menu_id = '9000';
INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
SELECT DISTINCT role_id, '9553' FROM sys_role_menu WHERE menu_id = '9000';
INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
SELECT DISTINCT role_id, '9554' FROM sys_role_menu WHERE menu_id = '9000';
