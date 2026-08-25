-- 自动外呼第三阶段：集群安全调度租约、成员原子领取和待拨调度单。

ALTER TABLE cc_outbound_task
    ADD COLUMN scheduler_owner VARCHAR(96) NULL COMMENT '当前调度实例标识' AFTER last_schedule_summary,
    ADD COLUMN scheduler_lease_until DATETIME NULL COMMENT '调度租约到期时间' AFTER scheduler_owner,
    ADD COLUMN scheduler_heartbeat_at DATETIME NULL COMMENT '调度实例最近心跳时间' AFTER scheduler_lease_until,
    ADD KEY idx_cc_outbound_task_scheduler (tenant_id, task_type, status, scheduler_lease_until);

ALTER TABLE cc_outbound_member
    MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        COMMENT '名单状态：PENDING待调度、SCHEDULED已调度、CLAIMED已领取、DIALING拨打中、COMPLETED已完成、RETRY待重呼、SKIPPED已跳过、BLOCKED已拦截',
    ADD COLUMN schedule_key VARCHAR(128) NULL COMMENT '自动外呼幂等调度键' AFTER lease_expires_at,
    ADD COLUMN scheduled_at DATETIME NULL COMMENT '最近调度时间' AFTER schedule_key,
    ADD UNIQUE KEY uk_cc_outbound_member_schedule_key (tenant_id, schedule_key, deleted),
    ADD KEY idx_cc_outbound_member_schedule (tenant_id, task_id, status, next_follow_up_at, scheduled_at);

CREATE TABLE cc_auto_outbound_dispatch (
    id BIGINT NOT NULL COMMENT '待拨调度单ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    task_id BIGINT NOT NULL COMMENT '自动外呼任务ID',
    member_id BIGINT NOT NULL COMMENT '名单成员ID',
    dispatch_key VARCHAR(128) NOT NULL COMMENT '幂等调度键',
    attempt_no INT NOT NULL COMMENT '计划拨打次数',
    previous_member_status VARCHAR(16) NOT NULL COMMENT '调度前名单状态',
    status VARCHAR(16) NOT NULL DEFAULT 'READY' COMMENT '调度状态：READY待拨、PROCESSING处理中、COMPLETED完成、CANCELLED取消',
    lease_owner VARCHAR(96) NULL COMMENT '消费实例标识',
    lease_expires_at DATETIME NULL COMMENT '消费租约到期时间',
    scheduled_at DATETIME NOT NULL COMMENT '进入调度时间',
    started_at DATETIME NULL COMMENT '开始拨打时间',
    completed_at DATETIME NULL COMMENT '处理完成时间',
    failure_reason VARCHAR(500) NULL COMMENT '调度或拨打失败原因',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_auto_dispatch_key (tenant_id, dispatch_key, deleted),
    KEY idx_cc_auto_dispatch_task (tenant_id, task_id, status, scheduled_at),
    KEY idx_cc_auto_dispatch_member (tenant_id, member_id, attempt_no),
    KEY idx_cc_auto_dispatch_lease (tenant_id, status, lease_expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自动外呼待拨调度单';
