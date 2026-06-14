CREATE TABLE cc_outbound_task (
    id BIGINT NOT NULL COMMENT '外呼任务ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    task_code VARCHAR(32) NOT NULL COMMENT '任务编码',
    task_name VARCHAR(64) NOT NULL COMMENT '任务名称',
    task_type VARCHAR(16) NOT NULL DEFAULT 'PREVIEW' COMMENT '任务类型：PREVIEW预览式外呼',
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT '任务状态：DRAFT草稿、RUNNING执行中、PAUSED已暂停、COMPLETED已完成',
    description VARCHAR(500) NULL COMMENT '任务说明',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_outbound_task_code (tenant_id, task_code, deleted),
    KEY idx_cc_outbound_task_status (tenant_id, status)
) ENGINE=InnoDB COMMENT='预览式外呼任务';

CREATE TABLE cc_outbound_member (
    id BIGINT NOT NULL COMMENT '外呼名单成员ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    task_id BIGINT NOT NULL COMMENT '外呼任务ID',
    customer_id BIGINT NOT NULL COMMENT '客户ID',
    customer_name VARCHAR(128) NULL COMMENT '客户名称快照',
    phone_number VARCHAR(32) NOT NULL COMMENT '外呼号码快照',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '名单状态：PENDING待领取、CLAIMED已领取、DIALING拨打中、COMPLETED已完成、RETRY待重呼、SKIPPED已跳过',
    claimed_agent_id BIGINT NULL COMMENT '领取坐席ID',
    claimed_user_id BIGINT NULL COMMENT '领取系统用户ID',
    claimed_at DATETIME NULL COMMENT '领取时间',
    business_call_id VARCHAR(64) NULL COMMENT '业务通话标识',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '已拨打次数',
    result_code VARCHAR(32) NULL COMMENT '外呼结果编码',
    result_remark VARCHAR(1000) NULL COMMENT '外呼结果备注',
    next_follow_up_at DATETIME NULL COMMENT '下次跟进时间',
    completed_at DATETIME NULL COMMENT '完成时间',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_outbound_member_customer (tenant_id, task_id, customer_id, deleted),
    KEY idx_cc_outbound_member_claim (tenant_id, task_id, status, claimed_user_id),
    KEY idx_cc_outbound_member_call (tenant_id, business_call_id)
) ENGINE=InnoDB COMMENT='预览式外呼名单成员';

ALTER TABLE cc_call_session
    ADD COLUMN outbound_task_id BIGINT NULL COMMENT '关联外呼任务ID' AFTER ticket_id,
    ADD COLUMN outbound_member_id BIGINT NULL COMMENT '关联外呼名单成员ID' AFTER outbound_task_id,
    ADD KEY idx_cc_call_session_outbound_task (tenant_id, outbound_task_id),
    ADD KEY idx_cc_call_session_outbound_member (tenant_id, outbound_member_id);

INSERT INTO sys_menu VALUES('9150', '外呼任务', '9200', '6', 'outbound-task', 'callcenter/outbound-task/index', '', 1, 0, 'C', '0', '0', 'callcenter:outbound-task:list', 'phone', 103, 1, SYSDATE(), NULL, NULL, '预览式外呼任务管理');
INSERT INTO sys_menu VALUES('9151', '外呼任务查询', '9150', '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:outbound-task:query', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT INTO sys_menu VALUES('9152', '外呼任务新增', '9150', '2', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:outbound-task:create', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT INTO sys_menu VALUES('9153', '外呼任务修改', '9150', '3', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:outbound-task:update', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT INTO sys_menu VALUES('9154', '外呼任务删除', '9150', '4', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:outbound-task:delete', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT INTO sys_menu VALUES('9155', '外呼任务执行', '9150', '5', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:outbound-task:execute', '#', 103, 1, SYSDATE(), NULL, NULL, '');
