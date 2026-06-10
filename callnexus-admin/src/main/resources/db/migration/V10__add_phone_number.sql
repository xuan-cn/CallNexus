CREATE TABLE cc_phone_number (
    id                  BIGINT          NOT NULL COMMENT 'Phone number ID',
    tenant_id           VARCHAR(20)     NOT NULL COMMENT 'Tenant ID',
    number              VARCHAR(32)     NOT NULL COMMENT 'DID or caller ID number',
    number_name         VARCHAR(64)     NOT NULL COMMENT 'Number display name',
    number_type         VARCHAR(16)     NOT NULL COMMENT 'Number type: DID/CALLER_ID/BOTH',
    node_id             BIGINT          NOT NULL COMMENT 'FreeSWITCH node ID',
    gateway_id          BIGINT          NULL COMMENT 'FreeSWITCH gateway ID',
    route_type          VARCHAR(32)     NOT NULL DEFAULT 'NONE' COMMENT 'Inbound route type: NONE/EXTENSION',
    route_target        VARCHAR(64)     NULL COMMENT 'Inbound route target, for example extension number',
    outbound_default    TINYINT         NOT NULL DEFAULT 0 COMMENT 'Whether default outbound caller ID',
    enabled             TINYINT         NOT NULL DEFAULT 1 COMMENT 'Whether enabled',
    create_dept         BIGINT          NULL,
    create_by           BIGINT          NULL,
    create_time         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by           BIGINT          NULL,
    update_time         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    version             INT             NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    deleted             TINYINT         NOT NULL DEFAULT 0 COMMENT 'Logical delete',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_phone_number_tenant_number (tenant_id, number, deleted),
    KEY idx_cc_phone_number_node (tenant_id, node_id),
    KEY idx_cc_phone_number_gateway (tenant_id, gateway_id),
    KEY idx_cc_phone_number_route (tenant_id, route_type, route_target)
) ENGINE=InnoDB COMMENT='Call center phone number';

INSERT INTO sys_menu VALUES('9008', '号码管理', '9000', '8', 'phone-number', 'callcenter/phone-number/index', '', 1, 0, 'C', '0', '0', 'callcenter:phone-number:list', 'phone', 103, 1, sysdate(), null, null, '呼叫中心号码管理菜单');
INSERT INTO sys_menu VALUES('9081', '号码查询', '9008', '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:phone-number:query', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9082', '号码新增', '9008', '2', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:phone-number:create', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9083', '号码修改', '9008', '3', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:phone-number:update', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9084', '号码删除', '9008', '4', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:phone-number:delete', '#', 103, 1, sysdate(), null, null, '');
