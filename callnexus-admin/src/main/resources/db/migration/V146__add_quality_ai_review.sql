ALTER TABLE cc_quality_template_item
    ADD COLUMN ai_review_enabled TINYINT(1) NOT NULL DEFAULT 1 AFTER evidence_required,
    ADD COLUMN ai_confidence_threshold DECIMAL(4,3) NOT NULL DEFAULT 0.700 AFTER ai_review_enabled,
    ADD COLUMN ai_prompt_hint VARCHAR(1000) NULL AFTER ai_confidence_threshold;

ALTER TABLE cc_quality_result
    ADD COLUMN ai_model_id BIGINT NULL AFTER source,
    ADD COLUMN raw_response_json LONGTEXT NULL AFTER ai_model_id;

ALTER TABLE cc_quality_item_result
    ADD COLUMN confidence DECIMAL(4,3) NULL AFTER score_change,
    ADD COLUMN modification_reason VARCHAR(1000) NULL AFTER manually_modified;

CREATE TABLE IF NOT EXISTS cc_quality_ai_review_task (
    id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    quality_task_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME NULL,
    lease_owner VARCHAR(64) NULL,
    lease_expires_at DATETIME NULL,
    model_id BIGINT NULL,
    request_json LONGTEXT NULL,
    response_json LONGTEXT NULL,
    error_message VARCHAR(2000) NULL,
    started_at DATETIME NULL,
    finished_at DATETIME NULL,
    create_dept BIGINT NULL, create_by BIGINT NULL, create_time DATETIME NULL,
    update_by BIGINT NULL, update_time DATETIME NULL,
    version INT NOT NULL DEFAULT 0, deleted TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_quality_ai_review_task (tenant_id, quality_task_id, create_time),
    KEY idx_quality_ai_review_dispatch (status, next_retry_at, lease_expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI质检初审任务';

SET @quality_workbench = (SELECT menu_id FROM sys_menu WHERE component = 'callcenter/quality-workbench/index' LIMIT 1);
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 2096800000000000117, 'AI初审', @quality_workbench, 3, '', '', '', 1, 0, 'F', '0', '0', 'callcenter:quality-task:ai-review', '#', 103, 1, SYSDATE(), NULL, NULL, ''
WHERE @quality_workbench IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'callcenter:quality-task:ai-review');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role.role_id, menu.menu_id FROM sys_role role
JOIN sys_menu menu ON menu.perms = 'callcenter:quality-task:ai-review'
WHERE role.role_key IN ('admin', 'cc_supervisor', 'cc_qa');
