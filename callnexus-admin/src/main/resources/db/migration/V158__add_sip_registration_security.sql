CREATE TABLE cc_sip_registration_security_event (
    id BIGINT NOT NULL COMMENT '事件ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户编号',
    event_key VARCHAR(96) NOT NULL COMMENT '节点事件幂等键',
    node_id BIGINT NOT NULL COMMENT 'FreeSWITCH节点ID',
    sip_account_id BIGINT NULL COMMENT 'SIP账号ID，未知账号时为空',
    extension_snapshot VARCHAR(32) NULL COMMENT '分机号快照',
    auth_username_snapshot VARCHAR(64) NULL COMMENT '鉴权名快照',
    source_ip VARCHAR(64) NULL COMMENT 'SIP终端来源IP',
    source_port INT NULL COMMENT 'SIP终端来源端口',
    transport VARCHAR(16) NULL COMMENT '传输协议',
    user_agent VARCHAR(255) NULL COMMENT '终端User-Agent',
    result VARCHAR(16) NOT NULL COMMENT '结果：SUCCESS/FAILURE/BLOCKED',
    reason_code VARCHAR(64) NULL COMMENT '标准化原因码',
    risk_level VARCHAR(16) NOT NULL DEFAULT 'LOW' COMMENT '风险等级',
    occurred_at DATETIME NOT NULL COMMENT '节点事件发生时间',
    create_dept BIGINT NULL,
    create_by BIGINT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by BIGINT NULL,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_sip_security_event_key (tenant_id, event_key),
    KEY idx_cc_sip_security_event_account_time (tenant_id, sip_account_id, occurred_at),
    KEY idx_cc_sip_security_event_ip_time (tenant_id, node_id, source_ip, occurred_at)
) ENGINE=InnoDB COMMENT='SIP分机注册安全事件';

CREATE TABLE cc_sip_registration_block (
    id BIGINT NOT NULL COMMENT '封禁ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户编号',
    node_id BIGINT NOT NULL COMMENT 'FreeSWITCH节点ID',
    sip_account_id BIGINT NULL COMMENT 'SIP账号ID',
    source_ip VARCHAR(64) NULL COMMENT '来源IP',
    block_type VARCHAR(20) NOT NULL COMMENT '封禁类型：IP/ACCOUNT/IP_ACCOUNT',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/EXPIRED/RELEASED',
    trigger_count INT NOT NULL DEFAULT 1 COMMENT '历史触发次数',
    blocked_at DATETIME NOT NULL COMMENT '封禁时间',
    expires_at DATETIME NULL COMMENT '自动解封时间，空表示手工锁定',
    reason_code VARCHAR(64) NOT NULL COMMENT '封禁原因码',
    reason_detail VARCHAR(255) NULL COMMENT '封禁原因说明',
    released_by BIGINT NULL COMMENT '解锁操作人',
    released_at DATETIME NULL COMMENT '解锁时间',
    release_reason VARCHAR(255) NULL COMMENT '解锁原因',
    create_dept BIGINT NULL,
    create_by BIGINT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by BIGINT NULL,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_cc_sip_block_account (tenant_id, sip_account_id, status, expires_at),
    KEY idx_cc_sip_block_ip (tenant_id, node_id, source_ip, status, expires_at)
) ENGINE=InnoDB COMMENT='SIP分机注册封禁状态';

INSERT IGNORE INTO sys_menu VALUES
('2099000000000000158', 'SIP注册安全锁定', '9001', '5', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:sip-security:block', '#', 103, 1, SYSDATE(), NULL, NULL, '允许锁定SIP分机'),
('2099000000000000159', 'SIP注册安全解锁', '9001', '6', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:sip-security:unblock', '#', 103, 1, SYSDATE(), NULL, NULL, '允许解除SIP分机注册锁定');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role_id, security_menu.menu_id
FROM sys_role
CROSS JOIN (
    SELECT '2099000000000000158' AS menu_id
    UNION ALL SELECT '2099000000000000159'
) security_menu
WHERE role_key IN ('superadmin', 'admin') AND del_flag = '0';
