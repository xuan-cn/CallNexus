ALTER TABLE cc_call_queue
    ADD COLUMN satisfaction_enabled TINYINT NOT NULL DEFAULT 0 COMMENT '是否在坐席先挂机后进入满意度评价' AFTER hangup_key_action,
    ADD COLUMN satisfaction_media_id BIGINT NULL COMMENT '满意度评价提示音媒体ID' AFTER satisfaction_enabled,
    ADD COLUMN satisfaction_timeout_seconds INT NOT NULL DEFAULT 8 COMMENT '满意度评价等待按键秒数' AFTER satisfaction_media_id;

CREATE TABLE cc_call_satisfaction (
    id BIGINT NOT NULL COMMENT '通话满意度评价ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    session_id BIGINT NOT NULL COMMENT '业务通话会话ID',
    business_call_id VARCHAR(64) NOT NULL COMMENT '业务通话ID',
    queue_id BIGINT NOT NULL COMMENT '呼叫队列ID',
    customer_leg_uuid VARCHAR(64) NOT NULL COMMENT '客户通话腿UUID',
    score TINYINT NULL COMMENT '满意度评分，取值1至5',
    digit VARCHAR(8) NULL COMMENT '客户实际按键',
    status VARCHAR(20) NOT NULL COMMENT '评价状态：SUBMITTED已评价、NO_INPUT未按键',
    submitted_at DATETIME NULL COMMENT '客户提交评价时间',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_call_satisfaction_session (tenant_id, session_id),
    KEY idx_cc_call_satisfaction_queue_time (tenant_id, queue_id, create_time),
    KEY idx_cc_call_satisfaction_business_call (tenant_id, business_call_id)
) ENGINE=InnoDB COMMENT='队列通话满意度评价';
