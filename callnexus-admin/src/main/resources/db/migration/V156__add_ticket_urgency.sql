CREATE TABLE cc_ticket_urgency_level (
    id              BIGINT          NOT NULL COMMENT 'Urgency level ID',
    tenant_id       VARCHAR(20)     NOT NULL COMMENT 'Tenant ID',
    level_code      VARCHAR(32)     NOT NULL COMMENT 'Stable urgency code',
    level_name      VARCHAR(32)     NOT NULL COMMENT 'Display name',
    priority_weight INT             NOT NULL DEFAULT 10 COMMENT 'Higher values sort first',
    tag_color       VARCHAR(16)     NOT NULL DEFAULT '#909399' COMMENT 'Display color',
    is_default      TINYINT         NOT NULL DEFAULT 0 COMMENT 'Default level',
    enabled         TINYINT         NOT NULL DEFAULT 1 COMMENT 'Available for selection',
    sort_order      INT             NOT NULL DEFAULT 0 COMMENT 'Option display order',
    remark          VARCHAR(255)    NULL,
    create_dept     BIGINT          NULL,
    create_by       BIGINT          NULL,
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by       BIGINT          NULL,
    update_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    version         INT             NOT NULL DEFAULT 0,
    deleted         TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_ticket_urgency_code (tenant_id, level_code, deleted),
    KEY idx_cc_ticket_urgency_options (tenant_id, enabled, sort_order)
) ENGINE=InnoDB COMMENT='Ticket urgency level configuration';

ALTER TABLE cc_ticket
    ADD COLUMN urgency_level_id BIGINT NULL COMMENT 'Urgency level ID' AFTER ticket_status,
    ADD COLUMN urgency_code VARCHAR(32) NOT NULL DEFAULT 'NORMAL' COMMENT 'Urgency code snapshot' AFTER urgency_level_id,
    ADD COLUMN urgency_name VARCHAR(32) NOT NULL DEFAULT '普通' COMMENT 'Urgency name snapshot' AFTER urgency_code,
    ADD COLUMN urgency_weight INT NOT NULL DEFAULT 10 COMMENT 'Urgency weight snapshot' AFTER urgency_name,
    ADD COLUMN urgency_color VARCHAR(16) NOT NULL DEFAULT '#909399' COMMENT 'Urgency color snapshot' AFTER urgency_weight,
    ADD KEY idx_cc_ticket_urgency (tenant_id, ticket_status, urgency_weight, create_time);

SET @ticket_menu_id = COALESCE(
    (SELECT menu_id FROM sys_menu WHERE component = 'callcenter/ticket/index' AND menu_type = 'C' LIMIT 1),
    (SELECT menu_id FROM sys_menu WHERE path = 'ticket' AND menu_type = 'C' LIMIT 1)
);

SET @urgency_manage_id = (SELECT CAST(COALESCE(MAX(CAST(menu_id AS DECIMAL(20, 0))), 9630) + 1 AS CHAR) FROM sys_menu WHERE CAST(menu_id AS CHAR) REGEXP '^[0-9]+$');
INSERT INTO sys_menu
SELECT @urgency_manage_id, '紧急程度配置', @ticket_menu_id, '20', '', '', '', 1, 0, 'F', '0', '0',
       'callcenter:ticket-urgency:manage', '#', 103, 1, SYSDATE(), NULL, NULL, ''
WHERE @ticket_menu_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'callcenter:ticket-urgency:manage');

SET @urgency_update_id = (SELECT CAST(MAX(CAST(menu_id AS DECIMAL(20, 0))) + 1 AS CHAR) FROM sys_menu WHERE CAST(menu_id AS CHAR) REGEXP '^[0-9]+$');
INSERT INTO sys_menu
SELECT @urgency_update_id, '调整工单紧急程度', @ticket_menu_id, '21', '', '', '', 1, 0, 'F', '0', '0',
       'callcenter:ticket:update-urgency', '#', 103, 1, SYSDATE(), NULL, NULL, ''
WHERE @ticket_menu_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'callcenter:ticket:update-urgency');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT source.role_id, permission.menu_id
FROM sys_role_menu source
JOIN sys_menu permission ON permission.perms IN ('callcenter:ticket-urgency:manage', 'callcenter:ticket:update-urgency')
WHERE source.menu_id = @ticket_menu_id;
