-- 第三方开放接口第一阶段：开放应用、客户端凭证、权限范围、来源 IP 和线路授权。
CREATE TABLE IF NOT EXISTS cc_openapi_application (
    id BIGINT NOT NULL COMMENT '开放应用ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    app_code VARCHAR(64) NOT NULL COMMENT '应用编码',
    app_name VARCHAR(128) NOT NULL COMMENT '应用名称',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    token_ttl_seconds INT NOT NULL DEFAULT 3600 COMMENT '访问令牌有效期（秒）',
    requests_per_minute INT NOT NULL DEFAULT 120 COMMENT '每分钟请求上限',
    max_concurrent_calls INT NOT NULL DEFAULT 10 COMMENT '最大并发通话数',
    description VARCHAR(500) NULL COMMENT '应用说明',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_openapi_application_code (tenant_id, app_code, deleted),
    KEY idx_cc_openapi_application_enabled (tenant_id, enabled)
) ENGINE=InnoDB COMMENT='第三方开放应用';

CREATE TABLE IF NOT EXISTS cc_openapi_credential (
    id BIGINT NOT NULL COMMENT '客户端凭证ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    application_id BIGINT NOT NULL COMMENT '开放应用ID',
    credential_name VARCHAR(128) NOT NULL COMMENT '凭证名称',
    client_id VARCHAR(80) NOT NULL COMMENT '客户端ID',
    secret_hash VARCHAR(100) NOT NULL COMMENT '客户端密钥BCrypt哈希',
    secret_hint VARCHAR(16) NOT NULL COMMENT '密钥末尾提示',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE、REVOKED',
    expires_at DATETIME NULL COMMENT '凭证失效时间',
    last_used_at DATETIME NULL COMMENT '最后使用时间',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_openapi_credential_client (client_id),
    KEY idx_cc_openapi_credential_app (tenant_id, application_id, status)
) ENGINE=InnoDB COMMENT='第三方开放应用客户端凭证';

CREATE TABLE IF NOT EXISTS cc_openapi_application_scope (
    id BIGINT NOT NULL COMMENT '应用权限ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    application_id BIGINT NOT NULL COMMENT '开放应用ID',
    scope_code VARCHAR(64) NOT NULL COMMENT '权限范围编码',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_openapi_application_scope (tenant_id, application_id, scope_code),
    KEY idx_cc_openapi_scope_app (tenant_id, application_id)
) ENGINE=InnoDB COMMENT='第三方开放应用权限范围';

CREATE TABLE IF NOT EXISTS cc_openapi_ip_rule (
    id BIGINT NOT NULL COMMENT '来源IP规则ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    application_id BIGINT NOT NULL COMMENT '开放应用ID',
    cidr VARCHAR(64) NOT NULL COMMENT '允许访问的IP或CIDR',
    description VARCHAR(255) NULL COMMENT '规则说明',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_openapi_ip_rule (tenant_id, application_id, cidr),
    KEY idx_cc_openapi_ip_rule_app (tenant_id, application_id, enabled)
) ENGINE=InnoDB COMMENT='第三方开放应用来源IP白名单';

CREATE TABLE IF NOT EXISTS cc_openapi_route_grant (
    id BIGINT NOT NULL COMMENT '线路授权ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    application_id BIGINT NOT NULL COMMENT '开放应用ID',
    route_policy_code VARCHAR(64) NOT NULL COMMENT '允许使用的外呼线路策略编码',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_openapi_route_grant (tenant_id, application_id, route_policy_code),
    KEY idx_cc_openapi_route_grant_app (tenant_id, application_id, enabled)
) ENGINE=InnoDB COMMENT='第三方开放应用外呼线路授权';

-- 第一阶段注册后台管理权限，页面菜单由后续迁移接入。
INSERT IGNORE INTO sys_menu VALUES('9520', '开放应用查询', '9000', '80', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:openapi-application:list', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9521', '开放应用详情', '9000', '81', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:openapi-application:query', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9522', '开放应用新增', '9000', '82', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:openapi-application:create', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9523', '开放应用修改', '9000', '83', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:openapi-application:update', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9524', '开放应用删除', '9000', '84', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:openapi-application:delete', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9525', '开放应用凭证', '9000', '85', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:openapi-application:credential', '#', 103, 1, SYSDATE(), NULL, NULL, '');
