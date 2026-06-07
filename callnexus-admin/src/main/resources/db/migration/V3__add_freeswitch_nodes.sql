CREATE TABLE cc_freeswitch_node (
    id              BIGINT          NOT NULL COMMENT 'FreeSWITCH node ID',
    tenant_id       VARCHAR(20)     NOT NULL COMMENT 'Tenant ID',
    node_code       VARCHAR(32)     NOT NULL COMMENT 'Node code',
    node_name       VARCHAR(64)     NOT NULL COMMENT 'Node name',
    sip_domain      VARCHAR(128)    NOT NULL COMMENT 'SIP domain',
    wss_url         VARCHAR(255)    NOT NULL COMMENT 'SIP over WSS URL',
    esl_host        VARCHAR(128)    NOT NULL COMMENT 'ESL host',
    esl_port        INT             NOT NULL DEFAULT 8021 COMMENT 'ESL port',
    esl_password    VARCHAR(255)    NOT NULL COMMENT 'Encrypted ESL password',
    enabled         TINYINT         NOT NULL DEFAULT 1 COMMENT 'Whether enabled',
    create_dept     BIGINT          NULL,
    create_by       BIGINT          NULL,
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by       BIGINT          NULL,
    update_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    version         INT             NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    deleted         TINYINT         NOT NULL DEFAULT 0 COMMENT 'Logical delete',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_freeswitch_node_tenant_code (tenant_id, node_code, deleted)
) ENGINE=InnoDB COMMENT='FreeSWITCH node';

ALTER TABLE cc_sip_account
    ADD COLUMN node_id BIGINT NULL COMMENT 'FreeSWITCH node ID' AFTER tenant_id,
    ADD KEY idx_cc_sip_account_node (tenant_id, node_id);

INSERT INTO sys_menu VALUES('9003', 'FreeSWITCH节点', '9000', '3', 'freeswitch-node', 'callcenter/freeswitch-node/index', '', 1, 0, 'C', '0', '0', 'callcenter:freeswitch-node:list', 'server', 103, 1, sysdate(), null, null, 'FreeSWITCH节点管理菜单');
INSERT INTO sys_menu VALUES('9031', 'FreeSWITCH节点查询', '9003', '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:freeswitch-node:query', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9032', 'FreeSWITCH节点新增', '9003', '2', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:freeswitch-node:create', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9033', 'FreeSWITCH节点修改', '9003', '3', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:freeswitch-node:update', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9034', 'FreeSWITCH节点删除', '9003', '4', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:freeswitch-node:delete', '#', 103, 1, sysdate(), null, null, '');
