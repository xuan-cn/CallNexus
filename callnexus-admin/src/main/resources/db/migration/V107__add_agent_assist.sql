-- 坐席辅助：技能组选择专用 AI 助手，客户最终分句异步生成建议回复。
ALTER TABLE cc_skill_group
    ADD COLUMN assist_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否启用通话坐席辅助' AFTER enabled,
    ADD COLUMN assist_agent_id BIGINT NULL COMMENT '坐席辅助使用的AI助手ID' AFTER assist_enabled,
    ADD KEY idx_cc_skill_group_assist_agent (tenant_id, assist_agent_id, deleted);

CREATE TABLE cc_agent_assist_session (
    id BIGINT NOT NULL COMMENT '主键',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    call_session_id BIGINT NOT NULL COMMENT '通话会话ID',
    business_call_id VARCHAR(64) NOT NULL COMMENT '业务通话ID',
    agent_id BIGINT NULL COMMENT '当前坐席ID',
    skill_group_id BIGINT NOT NULL COMMENT '技能组ID',
    assist_agent_id BIGINT NOT NULL COMMENT '坐席辅助AI助手ID',
    conversation_id BIGINT NULL COMMENT 'AI连续对话ID',
    session_state VARCHAR(24) NOT NULL DEFAULT 'ACTIVE' COMMENT '会话状态',
    started_at DATETIME NOT NULL COMMENT '开始时间',
    ended_at DATETIME NULL COMMENT '结束时间',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NULL COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NULL COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '版本号',
    deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_agent_assist_session_call (tenant_id, business_call_id, deleted),
    KEY idx_cc_agent_assist_session_call_session (tenant_id, call_session_id, deleted),
    KEY idx_cc_agent_assist_session_agent (tenant_id, agent_id, session_state, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通话坐席辅助会话';

CREATE TABLE cc_agent_assist_suggestion (
    id BIGINT NOT NULL COMMENT '主键',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    session_id BIGINT NOT NULL COMMENT '坐席辅助会话ID',
    transcript_segment_id BIGINT NOT NULL COMMENT '触发建议的客户转写分句ID',
    customer_text TEXT NOT NULL COMMENT '客户原话',
    suggested_reply TEXT NULL COMMENT '建议回复',
    source_type VARCHAR(32) NULL COMMENT '回答来源',
    status VARCHAR(24) NOT NULL COMMENT '处理状态',
    failure_reason VARCHAR(500) NULL COMMENT '失败原因',
    processing_ms BIGINT NULL COMMENT '处理耗时毫秒',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NULL COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NULL COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '版本号',
    deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_agent_assist_suggestion_segment (tenant_id, transcript_segment_id, deleted),
    KEY idx_cc_agent_assist_suggestion_session (tenant_id, session_id, id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通话坐席辅助逐句建议';
