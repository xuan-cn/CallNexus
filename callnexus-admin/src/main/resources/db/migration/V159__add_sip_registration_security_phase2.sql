CREATE TABLE cc_sip_registration_security_policy (
    id BIGINT NOT NULL COMMENT '策略ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户编号',
    node_id BIGINT NOT NULL DEFAULT 0 COMMENT '节点ID，0表示租户默认策略',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
    pair_window_seconds INT NOT NULL DEFAULT 60 COMMENT 'IP+分机统计窗口秒数',
    pair_failure_threshold INT NOT NULL DEFAULT 5 COMMENT 'IP+分机失败阈值',
    account_window_seconds INT NOT NULL DEFAULT 600 COMMENT '分机统计窗口秒数',
    account_failure_threshold INT NOT NULL DEFAULT 10 COMMENT '分机失败阈值',
    ip_window_seconds INT NOT NULL DEFAULT 300 COMMENT 'IP统计窗口秒数',
    ip_failure_threshold INT NOT NULL DEFAULT 20 COMMENT 'IP失败阈值',
    first_block_seconds INT NOT NULL DEFAULT 600 COMMENT '首次封禁秒数',
    second_block_seconds INT NOT NULL DEFAULT 1800 COMMENT '第二次封禁秒数',
    repeated_block_seconds INT NOT NULL DEFAULT 86400 COMMENT '多次触发封禁秒数',
    alert_enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否发送安全告警',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    create_dept BIGINT NULL,
    create_by BIGINT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by BIGINT NULL,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_sip_security_policy_scope (tenant_id, node_id)
) ENGINE=InnoDB COMMENT='SIP注册安全策略';

CREATE TABLE cc_sip_registration_execution_task (
    id BIGINT NOT NULL COMMENT '执行任务ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户编号',
    node_id BIGINT NOT NULL COMMENT '执行节点ID',
    block_id BIGINT NULL COMMENT '关联封禁ID',
    action VARCHAR(16) NOT NULL COMMENT '动作：BLOCK_IP/UNBLOCK_IP',
    source_ip VARCHAR(64) NOT NULL COMMENT '来源IP',
    expires_at DATETIME NULL COMMENT '封禁截止时间',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/PROCESSING/SUCCESS/FAILED',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '重试次数',
    next_retry_at DATETIME NULL COMMENT '下次重试时间',
    lease_token VARCHAR(64) NULL COMMENT '任务租约',
    lease_expires_at DATETIME NULL COMMENT '租约到期时间',
    failure_reason VARCHAR(1000) NULL COMMENT '失败原因',
    executed_at DATETIME NULL COMMENT '执行完成时间',
    idempotency_key VARCHAR(128) NOT NULL COMMENT '节点执行幂等键',
    create_dept BIGINT NULL,
    create_by BIGINT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by BIGINT NULL,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_sip_security_execution_key (tenant_id, idempotency_key),
    KEY idx_cc_sip_security_execution_claim (tenant_id, node_id, status, next_retry_at)
) ENGINE=InnoDB COMMENT='SIP注册安全节点执行任务';

ALTER TABLE cc_sip_registration_block
    ADD COLUMN execution_status VARCHAR(16) NULL COMMENT '节点执行状态：PENDING/SUCCESS/FAILED/NOT_REQUIRED' AFTER release_reason;

INSERT IGNORE INTO sys_menu VALUES
('2099000000000000160', '注册安全', '9203', '3', 'sip-security', 'callcenter/sip-security/index', '', 1, 0, 'C', '0', '0', 'callcenter:sip-security:list', 'lock', 103, 1, SYSDATE(), NULL, NULL, 'SIP分机注册安全管理'),
('2099000000000000161', '注册安全查询', '2099000000000000160', '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:sip-security:list', '#', 103, 1, SYSDATE(), NULL, NULL, ''),
('2099000000000000162', '注册安全策略配置', '2099000000000000160', '2', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:sip-security:policy', '#', 103, 1, SYSDATE(), NULL, NULL, ''),
('2099000000000000163', '注册安全ACL管理', '2099000000000000160', '3', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:sip-security:access-rule', '#', 103, 1, SYSDATE(), NULL, NULL, '');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role_id, security_menu.menu_id
FROM sys_role
CROSS JOIN (
    SELECT '2099000000000000160' AS menu_id
    UNION ALL SELECT '2099000000000000161'
    UNION ALL SELECT '2099000000000000162'
    UNION ALL SELECT '2099000000000000163'
) security_menu
WHERE role_key IN ('superadmin', 'admin') AND del_flag = '0';
