-- Capture model fallback answers for human review and FAQ publishing.
-- Use conditional DDL because MySQL auto-commits ALTER TABLE and a failed menu insert may leave this migration partially applied.
SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cc_ai_agent' AND COLUMN_NAME = 'faq_learning_enabled'),
    'SELECT 1',
    'ALTER TABLE cc_ai_agent ADD COLUMN faq_learning_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''Whether model fallback answers are collected'' AFTER retrieval_failure_policy'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cc_ai_agent' AND COLUMN_NAME = 'faq_learning_knowledge_base_id'),
    'SELECT 1',
    'ALTER TABLE cc_ai_agent ADD COLUMN faq_learning_knowledge_base_id BIGINT NULL COMMENT ''Target knowledge base for learned FAQ candidates'' AFTER faq_learning_enabled'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS cc_ai_faq_learning_candidate (
    id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL DEFAULT '000000',
    knowledge_base_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    user_message_id BIGINT NOT NULL,
    assistant_message_id BIGINT NOT NULL,
    source_channel VARCHAR(32) NOT NULL DEFAULT 'ONLINE_CHAT',
    standard_question VARCHAR(1000) NOT NULL,
    normalized_question VARCHAR(1000) NOT NULL,
    question_hash CHAR(64) NOT NULL,
    standard_answer MEDIUMTEXT NOT NULL,
    answer_hash CHAR(64) NOT NULL,
    faq_code VARCHAR(64) NULL,
    faq_name VARCHAR(128) NULL,
    aliases_json TEXT NULL,
    answer_mode VARCHAR(16) NOT NULL DEFAULT 'DIRECT',
    best_faq_score DECIMAL(8,6) NULL,
    best_document_score DECIMAL(8,6) NULL,
    occurrence_count INT NOT NULL DEFAULT 1,
    first_occurred_at DATETIME NOT NULL,
    last_occurred_at DATETIME NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    target_faq_id BIGINT NULL,
    review_reason VARCHAR(500) NULL,
    reviewed_by BIGINT NULL,
    reviewed_at DATETIME NULL,
    version INT NOT NULL DEFAULT 0,
    create_dept BIGINT NULL,
    create_by BIGINT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by BIGINT NULL,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_faq_learning_question (tenant_id, knowledge_base_id, question_hash, deleted),
    KEY idx_ai_faq_learning_review (tenant_id, status, last_occurred_at),
    KEY idx_ai_faq_learning_agent (tenant_id, agent_id, status)
) ENGINE=InnoDB COMMENT='Model fallback FAQ learning candidates';

-- Resolve the current AI strategy parent dynamically because menu order may be customized.
SET @ai_parent_id = COALESCE(
    (SELECT parent_id FROM sys_menu WHERE path = 'ai-agent' AND menu_type = 'C' LIMIT 1),
    (SELECT menu_id FROM sys_menu WHERE menu_name = 'AI策略管理' AND menu_type = 'M' LIMIT 1),
    '9320'
);
-- Keep menu id arithmetic in DECIMAL. Numeric user variables above 2^53 otherwise lose precision and id + 1 may equal id.
SET @next_menu_id = (
    SELECT CAST(COALESCE(MAX(CAST(menu_id AS DECIMAL(20, 0))), 9630) + 1 AS CHAR)
    FROM sys_menu WHERE CAST(menu_id AS CHAR) REGEXP '^[0-9]+$'
);
SET @learning_menu_id = COALESCE((SELECT menu_id FROM sys_menu WHERE perms = 'callcenter:ai-faq-learning:list' LIMIT 1), CAST(@next_menu_id AS CHAR));
INSERT INTO sys_menu
SELECT @learning_menu_id, 'FAQ学习审核', @ai_parent_id, '5', 'ai-faq-learning', 'callcenter/ai-faq-learning/index', '', 1, 0, 'C', '0', '0', 'callcenter:ai-faq-learning:list', 'chat-check', 103, 1, SYSDATE(), NULL, NULL, '审核模型兜底回答并发布到FAQ知识库'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'callcenter:ai-faq-learning:list');

SET @query_menu_id = (SELECT CAST(MAX(CAST(menu_id AS DECIMAL(20, 0))) + 1 AS CHAR) FROM sys_menu WHERE CAST(menu_id AS CHAR) REGEXP '^[0-9]+$');
INSERT INTO sys_menu SELECT @query_menu_id, 'FAQ学习查询', @learning_menu_id, '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-faq-learning:query', '#', 103, 1, SYSDATE(), NULL, NULL, '' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'callcenter:ai-faq-learning:query');
SET @approve_menu_id = (SELECT CAST(MAX(CAST(menu_id AS DECIMAL(20, 0))) + 1 AS CHAR) FROM sys_menu WHERE CAST(menu_id AS CHAR) REGEXP '^[0-9]+$');
INSERT INTO sys_menu SELECT @approve_menu_id, 'FAQ学习发布', @learning_menu_id, '2', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-faq-learning:approve', '#', 103, 1, SYSDATE(), NULL, NULL, '' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'callcenter:ai-faq-learning:approve');
SET @merge_menu_id = (SELECT CAST(MAX(CAST(menu_id AS DECIMAL(20, 0))) + 1 AS CHAR) FROM sys_menu WHERE CAST(menu_id AS CHAR) REGEXP '^[0-9]+$');
INSERT INTO sys_menu SELECT @merge_menu_id, 'FAQ学习合并', @learning_menu_id, '3', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-faq-learning:merge', '#', 103, 1, SYSDATE(), NULL, NULL, '' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'callcenter:ai-faq-learning:merge');
SET @reject_menu_id = (SELECT CAST(MAX(CAST(menu_id AS DECIMAL(20, 0))) + 1 AS CHAR) FROM sys_menu WHERE CAST(menu_id AS CHAR) REGEXP '^[0-9]+$');
INSERT INTO sys_menu SELECT @reject_menu_id, 'FAQ学习驳回', @learning_menu_id, '4', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-faq-learning:reject', '#', 103, 1, SYSDATE(), NULL, NULL, '' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'callcenter:ai-faq-learning:reject');
SET @review_menu_id = (SELECT CAST(MAX(CAST(menu_id AS DECIMAL(20, 0))) + 1 AS CHAR) FROM sys_menu WHERE CAST(menu_id AS CHAR) REGEXP '^[0-9]+$');
INSERT INTO sys_menu SELECT @review_menu_id, 'FAQ学习重开', @learning_menu_id, '5', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-faq-learning:review', '#', 103, 1, SYSDATE(), NULL, NULL, '' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'callcenter:ai-faq-learning:review');
