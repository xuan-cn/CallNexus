-- FreeSWITCH ACL 草稿、发布版本及管理菜单。
CREATE TABLE IF NOT EXISTS cc_freeswitch_acl (
    id BIGINT NOT NULL COMMENT 'ACL ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    node_id BIGINT NOT NULL COMMENT 'FreeSWITCH节点ID',
    acl_code VARCHAR(64) NOT NULL COMMENT 'FreeSWITCH ACL列表编码',
    acl_name VARCHAR(128) NOT NULL COMMENT 'ACL名称',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：SIP_ENDPOINT、CARRIER_INGRESS',
    default_action VARCHAR(16) NOT NULL COMMENT '默认动作：ALLOW、DENY',
    entries_json LONGTEXT NOT NULL COMMENT 'ACL规则JSON',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    published_version_id BIGINT NULL COMMENT '当前发布版本ID',
    published_version_no INT NULL COMMENT '当前发布版本号',
    sync_status VARCHAR(32) NOT NULL DEFAULT 'NOT_PUBLISHED' COMMENT '同步状态',
    sync_error VARCHAR(1000) NULL COMMENT '同步失败原因',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_freeswitch_acl_code (tenant_id, node_id, acl_code, deleted),
    KEY idx_cc_freeswitch_acl_node (tenant_id, node_id, enabled)
) ENGINE=InnoDB COMMENT='FreeSWITCH访问控制列表';

CREATE TABLE IF NOT EXISTS cc_freeswitch_acl_version (
    id BIGINT NOT NULL COMMENT 'ACL版本ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    acl_id BIGINT NOT NULL COMMENT 'ACL ID',
    node_id BIGINT NOT NULL COMMENT 'FreeSWITCH节点ID',
    version_no INT NOT NULL COMMENT '版本号',
    snapshot_json LONGTEXT NOT NULL COMMENT '不可变配置快照JSON',
    current_version TINYINT NOT NULL DEFAULT 0 COMMENT '是否当前生效版本',
    published_at DATETIME NOT NULL COMMENT '发布时间',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_freeswitch_acl_version (tenant_id, acl_id, version_no),
    KEY idx_cc_freeswitch_acl_current (tenant_id, node_id, current_version)
) ENGINE=InnoDB COMMENT='FreeSWITCH ACL发布版本';

INSERT IGNORE INTO sys_menu VALUES(
    '9493', '访问控制ACL', '9204', '3', 'freeswitch-acl', 'callcenter/freeswitch-acl/index', '', 1, 0, 'C', '0', '0',
    'callcenter:freeswitch-acl:list', 'lock', 103, 1, SYSDATE(), NULL, NULL, 'FreeSWITCH SIP与线路来源IP访问控制'
);
INSERT IGNORE INTO sys_menu VALUES('9494', 'ACL查询', '9493', '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:freeswitch-acl:query', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9495', 'ACL新增', '9493', '2', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:freeswitch-acl:create', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9496', 'ACL修改', '9493', '3', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:freeswitch-acl:update', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9497', 'ACL删除', '9493', '4', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:freeswitch-acl:delete', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9498', 'ACL发布', '9493', '5', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:freeswitch-acl:publish', '#', 103, 1, SYSDATE(), NULL, NULL, '');

-- 已具备 FreeSWITCH 节点菜单权限的角色自动获得 ACL 菜单。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9493' FROM sys_role_menu WHERE menu_id = '9003';
