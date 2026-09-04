-- Scale AI intent management with reusable groups and paged administration.
CREATE TABLE IF NOT EXISTS cc_ai_intent_group (
    id BIGINT NOT NULL COMMENT '意图分类ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    group_code VARCHAR(64) NOT NULL COMMENT '分类编码',
    group_name VARCHAR(128) NOT NULL COMMENT '分类名称',
    description VARCHAR(500) NULL COMMENT '分类说明',
    sort_order INT NOT NULL DEFAULT 100 COMMENT '排序',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_ai_intent_group_code (tenant_id, group_code, deleted),
    KEY idx_cc_ai_intent_group_order (tenant_id, enabled, sort_order)
) ENGINE=InnoDB COMMENT='AI意图分类表';

ALTER TABLE cc_ai_intent
    ADD COLUMN group_id BIGINT NULL COMMENT '意图分类ID' AFTER tenant_id,
    ADD KEY idx_cc_ai_intent_group (tenant_id, group_id, enabled, priority);
