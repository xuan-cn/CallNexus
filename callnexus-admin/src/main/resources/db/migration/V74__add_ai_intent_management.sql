-- AI 意图管理、相似话术、助手绑定和识别诊断。
CREATE TABLE IF NOT EXISTS cc_ai_intent (
    id BIGINT NOT NULL COMMENT '意图ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    intent_code VARCHAR(64) NOT NULL COMMENT '意图编码',
    intent_name VARCHAR(128) NOT NULL COMMENT '意图名称',
    intent_type VARCHAR(32) NOT NULL COMMENT '意图类型：CONVERSATION、CONTROL、ROUTING、BUSINESS',
    description VARCHAR(500) NULL COMMENT '意图说明',
    action_type VARCHAR(32) NOT NULL DEFAULT 'NONE' COMMENT '受控动作类型',
    action_config_json TEXT NULL COMMENT '动作参数JSON，仅保存受控动作参数',
    response_template VARCHAR(2000) NULL COMMENT '命中后的建议回复',
    confidence_threshold DECIMAL(5,4) NOT NULL DEFAULT 0.8000 COMMENT '默认置信度阈值',
    priority INT NOT NULL DEFAULT 100 COMMENT '优先级，数值越小越优先',
    confirmation_required TINYINT NOT NULL DEFAULT 0 COMMENT '执行动作前是否需要确认',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_ai_intent_code (tenant_id, intent_code, deleted),
    KEY idx_cc_ai_intent_enabled (tenant_id, enabled, intent_type, priority)
) ENGINE=InnoDB COMMENT='AI意图定义表';

CREATE TABLE IF NOT EXISTS cc_ai_intent_utterance (
    id BIGINT NOT NULL COMMENT '话术ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    intent_id BIGINT NOT NULL COMMENT '意图ID',
    utterance_type VARCHAR(16) NOT NULL COMMENT '话术类型：POSITIVE、NEGATIVE',
    utterance_text VARCHAR(1000) NOT NULL COMMENT '原始话术',
    normalized_text VARCHAR(1000) NOT NULL COMMENT '规范化话术',
    text_hash CHAR(64) NOT NULL COMMENT '规范化话术SHA-256',
    sort_order INT NOT NULL DEFAULT 100 COMMENT '排序',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_ai_intent_utterance (tenant_id, intent_id, utterance_type, text_hash, deleted),
    KEY idx_cc_ai_intent_utterance_lookup (tenant_id, intent_id, utterance_type)
) ENGINE=InnoDB COMMENT='AI意图正反例话术表';

CREATE TABLE IF NOT EXISTS cc_ai_agent_intent (
    id BIGINT NOT NULL COMMENT '助手意图绑定ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    agent_id BIGINT NOT NULL COMMENT 'AI助手ID',
    intent_id BIGINT NOT NULL COMMENT '意图ID',
    priority INT NOT NULL DEFAULT 100 COMMENT '助手内优先级',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_ai_agent_intent (tenant_id, agent_id, intent_id, deleted),
    KEY idx_cc_ai_agent_intent_agent (tenant_id, agent_id, enabled, priority)
) ENGINE=InnoDB COMMENT='AI助手意图绑定表';

CREATE TABLE IF NOT EXISTS cc_ai_intent_recognition_log (
    id BIGINT NOT NULL COMMENT '识别日志ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    agent_id BIGINT NOT NULL COMMENT 'AI助手ID',
    conversation_id BIGINT NULL COMMENT '对话ID',
    message_id BIGINT NULL COMMENT '消息ID',
    input_text VARCHAR(2000) NOT NULL COMMENT '用户原始文本',
    normalized_text VARCHAR(2000) NOT NULL COMMENT '规范化文本',
    intent_id BIGINT NULL COMMENT '命中意图ID',
    intent_code VARCHAR(64) NULL COMMENT '命中意图编码',
    intent_name VARCHAR(128) NULL COMMENT '命中意图名称',
    confidence DECIMAL(8,6) NULL COMMENT '识别置信度',
    match_method VARCHAR(16) NOT NULL COMMENT '匹配方式：EXACT、MODEL、NONE',
    recognition_status VARCHAR(16) NOT NULL COMMENT '状态：MATCHED、UNMATCHED、FAILED',
    reason VARCHAR(1000) NULL COMMENT '诊断说明',
    latency_ms BIGINT NOT NULL DEFAULT 0 COMMENT '识别耗时毫秒',
    model_id BIGINT NULL COMMENT '分类使用的Chat模型ID',
    raw_response MEDIUMTEXT NULL COMMENT '模型原始响应',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    KEY idx_cc_ai_intent_log_agent (tenant_id, agent_id, create_time),
    KEY idx_cc_ai_intent_log_intent (tenant_id, intent_id, create_time)
) ENGINE=InnoDB COMMENT='AI意图识别诊断日志表';

INSERT IGNORE INTO sys_menu VALUES('9350', '意图管理', '9320', '4', 'ai-intent', 'callcenter/ai-intent/index', '', 1, 0, 'C', '0', '0', 'callcenter:ai-intent:list', 'guide', 103, 1, SYSDATE(), NULL, NULL, 'AI意图、相似话术、助手绑定和识别测试');
INSERT IGNORE INTO sys_menu VALUES('9351', '意图查询', '9350', '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-intent:query', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9352', '意图新增', '9350', '2', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-intent:create', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9353', '意图修改', '9350', '3', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-intent:update', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9354', '意图删除', '9350', '4', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-intent:delete', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9355', '意图测试', '9350', '5', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-intent:test', '#', 103, 1, SYSDATE(), NULL, NULL, '');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, menu_id FROM (
    SELECT role_id, '9350' menu_id FROM sys_role_menu WHERE menu_id IN ('9320', '9340')
    UNION ALL SELECT role_id, '9351' FROM sys_role_menu WHERE menu_id IN ('9320', '9340')
    UNION ALL SELECT role_id, '9352' FROM sys_role_menu WHERE menu_id IN ('9320', '9340')
    UNION ALL SELECT role_id, '9353' FROM sys_role_menu WHERE menu_id IN ('9320', '9340')
    UNION ALL SELECT role_id, '9354' FROM sys_role_menu WHERE menu_id IN ('9320', '9340')
    UNION ALL SELECT role_id, '9355' FROM sys_role_menu WHERE menu_id IN ('9320', '9340')
) permissions;
