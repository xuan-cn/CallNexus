CREATE TABLE cc_freeswitch_gateway (
    id                  BIGINT          NOT NULL COMMENT 'FreeSWITCH gateway ID',
    tenant_id           VARCHAR(20)     NOT NULL COMMENT 'Tenant ID',
    node_id             BIGINT          NOT NULL COMMENT 'FreeSWITCH node ID',
    gateway_code        VARCHAR(32)     NOT NULL COMMENT 'Gateway code',
    gateway_name        VARCHAR(64)     NOT NULL COMMENT 'Gateway name',
    direction           VARCHAR(16)     NOT NULL COMMENT 'Gateway direction: INBOUND/OUTBOUND/BOTH',
    proxy               VARCHAR(128)    NOT NULL COMMENT 'SIP proxy host or host:port',
    realm               VARCHAR(128)    NULL COMMENT 'SIP realm',
    username            VARCHAR(64)     NULL COMMENT 'Auth username',
    password            VARCHAR(255)    NULL COMMENT 'Encrypted auth password',
    register_enabled    TINYINT         NOT NULL DEFAULT 0 COMMENT 'Whether FreeSWITCH should register to gateway',
    transport           VARCHAR(8)      NOT NULL DEFAULT 'UDP' COMMENT 'Transport: UDP/TCP/TLS',
    caller_id_number    VARCHAR(32)     NULL COMMENT 'Default caller ID number',
    ping                INT             NOT NULL DEFAULT 0 COMMENT 'Gateway OPTIONS ping interval seconds, 0 means disabled',
    enabled             TINYINT         NOT NULL DEFAULT 1 COMMENT 'Whether enabled',
    create_dept         BIGINT          NULL,
    create_by           BIGINT          NULL,
    create_time         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by           BIGINT          NULL,
    update_time         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    version             INT             NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    deleted             TINYINT         NOT NULL DEFAULT 0 COMMENT 'Logical delete',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_freeswitch_gateway_tenant_code (tenant_id, gateway_code, deleted),
    KEY idx_cc_freeswitch_gateway_node (tenant_id, node_id),
    KEY idx_cc_freeswitch_gateway_direction (tenant_id, direction)
) ENGINE=InnoDB COMMENT='FreeSWITCH gateway';

INSERT INTO sys_menu VALUES('9007', '网关管理', '9000', '7', 'freeswitch-gateway', 'callcenter/freeswitch-gateway/index', '', 1, 0, 'C', '0', '0', 'callcenter:freeswitch-gateway:list', 'link', 103, 1, sysdate(), null, null, 'FreeSWITCH 网关管理菜单');
INSERT INTO sys_menu VALUES('9071', '网关查询', '9007', '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:freeswitch-gateway:query', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9072', '网关新增', '9007', '2', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:freeswitch-gateway:create', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9073', '网关修改', '9007', '3', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:freeswitch-gateway:update', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9074', '网关删除', '9007', '4', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:freeswitch-gateway:delete', '#', 103, 1, sysdate(), null, null, '');
