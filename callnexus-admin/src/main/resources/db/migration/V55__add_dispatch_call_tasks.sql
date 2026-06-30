CREATE TABLE cc_dispatch_call_task (
    id BIGINT NOT NULL COMMENT '调度呼叫任务ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    business_call_id VARCHAR(64) NOT NULL COMMENT '业务通话ID',
    node_id BIGINT NOT NULL COMMENT 'FreeSWITCH节点ID',
    operator_user_id BIGINT NOT NULL COMMENT '发起调度的系统用户ID',
    operator_sip_account_id BIGINT NOT NULL COMMENT '调度分机SIP账号ID',
    operator_extension VARCHAR(32) NOT NULL COMMENT '调度分机号',
    operator_leg_uuid VARCHAR(64) NOT NULL COMMENT '调度分机电话腿UUID',
    conference_name VARCHAR(128) NOT NULL COMMENT 'FreeSWITCH会议名称',
    task_type VARCHAR(16) NOT NULL COMMENT '任务类型：SINGLE单呼、GROUP组呼',
    task_state VARCHAR(32) NOT NULL COMMENT '任务状态：STARTING、RUNNING、SUCCESS、PARTIAL、FAILED、CANCELLED',
    total_count INT NOT NULL DEFAULT 0 COMMENT '目标总数',
    answered_count INT NOT NULL DEFAULT 0 COMMENT '已接听目标数',
    failed_count INT NOT NULL DEFAULT 0 COMMENT '失败目标数',
    cancelled_count INT NOT NULL DEFAULT 0 COMMENT '已取消目标数',
    started_at DATETIME NOT NULL COMMENT '任务开始时间',
    ended_at DATETIME NULL COMMENT '任务结束时间',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_dispatch_call_business (tenant_id, business_call_id),
    UNIQUE KEY uk_dispatch_call_operator_leg (operator_leg_uuid),
    KEY idx_dispatch_call_task_time (tenant_id, create_time),
    KEY idx_dispatch_call_task_state (tenant_id, task_state, started_at)
) ENGINE=InnoDB COMMENT='调度单呼与组呼任务表';

CREATE TABLE cc_dispatch_call_target (
    id BIGINT NOT NULL COMMENT '调度呼叫目标ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    task_id BIGINT NOT NULL COMMENT '调度呼叫任务ID',
    node_id BIGINT NOT NULL COMMENT 'FreeSWITCH节点ID',
    sip_account_id BIGINT NOT NULL COMMENT '目标SIP账号ID',
    target_extension VARCHAR(32) NOT NULL COMMENT '目标分机号',
    target_leg_uuid VARCHAR(64) NOT NULL COMMENT '目标电话腿UUID',
    target_state VARCHAR(32) NOT NULL COMMENT '目标状态：PENDING、SUBMITTED、RINGING、ANSWERED、ENDED、FAILED、CANCELLED',
    answered TINYINT NOT NULL DEFAULT 0 COMMENT '是否曾经接听',
    failure_reason VARCHAR(500) NULL COMMENT '失败或取消原因',
    submitted_at DATETIME NULL COMMENT '命令提交时间',
    ringing_at DATETIME NULL COMMENT '开始振铃时间',
    answered_at DATETIME NULL COMMENT '接听时间',
    ended_at DATETIME NULL COMMENT '结束时间',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_dispatch_target_leg (target_leg_uuid),
    UNIQUE KEY uk_dispatch_target_extension (tenant_id, task_id, target_extension),
    KEY idx_dispatch_target_task_state (tenant_id, task_id, target_state)
) ENGINE=InnoDB COMMENT='调度呼叫目标明细表';

INSERT IGNORE INTO sys_menu VALUES('9309', '调度单呼', '9300', '9', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:dispatch-control:call', '#', 103, 1, SYSDATE(), NULL, NULL, '允许调度员呼叫单个SIP分机');
INSERT IGNORE INTO sys_menu VALUES('9310', '调度组呼', '9300', '10', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:dispatch-control:group-call', '#', 103, 1, SYSDATE(), NULL, NULL, '允许调度员同时呼叫多个SIP分机');
INSERT IGNORE INTO sys_menu VALUES('9311', '调度呼叫任务查询', '9300', '11', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:dispatch-call-task:list', '#', 103, 1, SYSDATE(), NULL, NULL, '查询调度单呼和组呼任务');
INSERT IGNORE INTO sys_menu VALUES('9312', '调度呼叫任务详情', '9300', '12', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:dispatch-call-task:query', '#', 103, 1, SYSDATE(), NULL, NULL, '查询调度呼叫目标状态');
INSERT IGNORE INTO sys_menu VALUES('9313', '停止未接听调度目标', '9300', '13', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:dispatch-control:stop-group', '#', 103, 1, SYSDATE(), NULL, NULL, '停止调度任务中仍未接听的目标分机');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, permission.menu_id
FROM sys_role_menu source
CROSS JOIN (
    SELECT '9309' AS menu_id
    UNION ALL SELECT '9310'
    UNION ALL SELECT '9311'
    UNION ALL SELECT '9312'
    UNION ALL SELECT '9313'
) permission
WHERE source.menu_id IN ('9300', '9301');
