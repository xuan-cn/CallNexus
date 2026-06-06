CREATE TABLE cc_sip_account (
    id              BIGINT          NOT NULL COMMENT 'SIP账号ID',
    tenant_id       VARCHAR(20)     NOT NULL COMMENT '租户编号',
    extension       VARCHAR(32)     NOT NULL COMMENT '分机号',
    display_name    VARCHAR(64)     NOT NULL COMMENT '显示名称',
    domain          VARCHAR(128)    NOT NULL COMMENT 'SIP域',
    auth_password   VARCHAR(255)    NOT NULL COMMENT '认证密钥，接入密钥服务后加密存储',
    enabled         TINYINT         NOT NULL DEFAULT 1 COMMENT '是否启用',
    create_dept     BIGINT          NULL,
    create_by       BIGINT          NULL,
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by       BIGINT          NULL,
    update_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    version         INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted         TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_sip_account_tenant_extension (tenant_id, extension, deleted),
    KEY idx_cc_sip_account_domain (tenant_id, domain)
) ENGINE=InnoDB COMMENT='SIP账号';

CREATE TABLE cc_agent (
    id              BIGINT          NOT NULL COMMENT '坐席ID',
    tenant_id       VARCHAR(20)     NOT NULL COMMENT '租户编号',
    agent_code      VARCHAR(32)     NOT NULL COMMENT '坐席编码',
    agent_name      VARCHAR(64)     NOT NULL COMMENT '坐席名称',
    user_id         BIGINT          NULL COMMENT '系统用户ID',
    enabled         TINYINT         NOT NULL DEFAULT 1 COMMENT '是否启用',
    create_dept     BIGINT          NULL,
    create_by       BIGINT          NULL,
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by       BIGINT          NULL,
    update_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    version         INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted         TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_agent_tenant_code (tenant_id, agent_code, deleted),
    UNIQUE KEY uk_cc_agent_tenant_user (tenant_id, user_id, deleted)
) ENGINE=InnoDB COMMENT='坐席';

CREATE TABLE cc_agent_extension (
    id              BIGINT          NOT NULL COMMENT '绑定ID',
    tenant_id       VARCHAR(20)     NOT NULL COMMENT '租户编号',
    agent_id        BIGINT          NOT NULL COMMENT '坐席ID',
    sip_account_id  BIGINT          NOT NULL COMMENT 'SIP账号ID',
    create_dept     BIGINT          NULL,
    create_by       BIGINT          NULL,
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by       BIGINT          NULL,
    update_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    version         INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted         TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_agent_extension_agent (tenant_id, agent_id, deleted),
    UNIQUE KEY uk_cc_agent_extension_sip (tenant_id, sip_account_id, deleted)
) ENGINE=InnoDB COMMENT='坐席分机绑定';
