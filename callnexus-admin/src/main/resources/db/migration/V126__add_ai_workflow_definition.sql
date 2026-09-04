-- AI workflow definitions, immutable published versions and agent scene bindings.
CREATE TABLE cc_ai_workflow (
    id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL DEFAULT '000000',
    workflow_code VARCHAR(64) NOT NULL,
    workflow_name VARCHAR(128) NOT NULL,
    scene_type VARCHAR(32) NOT NULL DEFAULT 'COMMON',
    description VARCHAR(500) NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    current_published_version_id BIGINT NULL,
    version INT NOT NULL DEFAULT 0,
    create_dept BIGINT NULL,
    create_by BIGINT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by BIGINT NULL,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_workflow_code (tenant_id, workflow_code, deleted),
    KEY idx_ai_workflow_scene (tenant_id, scene_type, enabled)
) ENGINE=InnoDB COMMENT='AI workflow definition';

CREATE TABLE cc_ai_workflow_version (
    id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL DEFAULT '000000',
    workflow_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    version_name VARCHAR(128) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    definition_json LONGTEXT NOT NULL,
    definition_hash CHAR(64) NOT NULL,
    published_by BIGINT NULL,
    published_at DATETIME NULL,
    create_dept BIGINT NULL,
    create_by BIGINT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by BIGINT NULL,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_workflow_version (tenant_id, workflow_id, version_no),
    KEY idx_ai_workflow_version_status (tenant_id, workflow_id, status)
) ENGINE=InnoDB COMMENT='AI workflow versions';

CREATE TABLE cc_ai_agent_workflow_binding (
    id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL DEFAULT '000000',
    ai_agent_id BIGINT NOT NULL,
    scene_type VARCHAR(32) NOT NULL,
    workflow_id BIGINT NOT NULL,
    workflow_version_id BIGINT NOT NULL,
    fallback_action VARCHAR(32) NOT NULL DEFAULT 'DEFAULT_CONVERSATION',
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    create_dept BIGINT NULL,
    create_by BIGINT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by BIGINT NULL,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_agent_workflow_scene (tenant_id, ai_agent_id, scene_type),
    KEY idx_ai_agent_workflow_version (tenant_id, workflow_version_id)
) ENGINE=InnoDB COMMENT='AI agent workflow scene binding';

SET @ai_parent_id = COALESCE(
    (SELECT menu_id FROM sys_menu WHERE menu_id = '9320' LIMIT 1),
    (SELECT menu_id FROM sys_menu WHERE path = 'callcenter-ai' AND menu_type = 'M' LIMIT 1)
);
SET @ai_workflow_menu_id = COALESCE(
    (SELECT menu_id FROM sys_menu WHERE parent_id = @ai_parent_id AND path = 'ai-workflow' LIMIT 1),
    (SELECT CAST(MAX(CAST(menu_id AS DECIMAL(20, 0))) + 1 AS CHAR) FROM sys_menu WHERE CAST(menu_id AS CHAR) REGEXP '^[0-9]+$')
);
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, query_param,
    is_frame, is_cache, menu_type, visible, status, perms, icon,
    create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT @ai_workflow_menu_id, 'AI编排', @ai_parent_id, 6, 'ai-workflow',
       'callcenter/ai-workflow/index', '', 1, 0, 'C', '0', '0',
       'callcenter:ai-workflow:list', 'connection', 103, 1, SYSDATE(), NULL, NULL,
       'AI多轮对话与业务动作编排'
WHERE @ai_parent_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE parent_id = @ai_parent_id AND path = 'ai-workflow');

SET @permission_id = (SELECT CAST(MAX(CAST(menu_id AS DECIMAL(20, 0))) + 1 AS CHAR) FROM sys_menu WHERE CAST(menu_id AS CHAR) REGEXP '^[0-9]+$');
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT @permission_id, 'AI编排查询', @ai_workflow_menu_id, 1, '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-workflow:query', '#', 103, 1, SYSDATE(), NULL, NULL, ''
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'callcenter:ai-workflow:query');
SET @permission_id = (SELECT CAST(MAX(CAST(menu_id AS DECIMAL(20, 0))) + 1 AS CHAR) FROM sys_menu WHERE CAST(menu_id AS CHAR) REGEXP '^[0-9]+$');
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT @permission_id, 'AI编排新增', @ai_workflow_menu_id, 2, '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-workflow:create', '#', 103, 1, SYSDATE(), NULL, NULL, ''
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'callcenter:ai-workflow:create');
SET @permission_id = (SELECT CAST(MAX(CAST(menu_id AS DECIMAL(20, 0))) + 1 AS CHAR) FROM sys_menu WHERE CAST(menu_id AS CHAR) REGEXP '^[0-9]+$');
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT @permission_id, 'AI编排修改', @ai_workflow_menu_id, 3, '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-workflow:edit', '#', 103, 1, SYSDATE(), NULL, NULL, ''
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'callcenter:ai-workflow:edit');
SET @permission_id = (SELECT CAST(MAX(CAST(menu_id AS DECIMAL(20, 0))) + 1 AS CHAR) FROM sys_menu WHERE CAST(menu_id AS CHAR) REGEXP '^[0-9]+$');
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT @permission_id, 'AI编排删除', @ai_workflow_menu_id, 4, '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-workflow:delete', '#', 103, 1, SYSDATE(), NULL, NULL, ''
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'callcenter:ai-workflow:delete');
SET @permission_id = (SELECT CAST(MAX(CAST(menu_id AS DECIMAL(20, 0))) + 1 AS CHAR) FROM sys_menu WHERE CAST(menu_id AS CHAR) REGEXP '^[0-9]+$');
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT @permission_id, 'AI编排发布', @ai_workflow_menu_id, 5, '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-workflow:publish', '#', 103, 1, SYSDATE(), NULL, NULL, ''
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'callcenter:ai-workflow:publish');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_menu.role_id, @ai_workflow_menu_id
FROM sys_role_menu role_menu
WHERE role_menu.menu_id = @ai_parent_id;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT source.role_id, permission.menu_id
FROM sys_role_menu source
JOIN sys_menu permission ON permission.parent_id = @ai_workflow_menu_id
WHERE source.menu_id = @ai_parent_id;

-- The designer reads existing intent and queue definitions as business options.
-- Grant their list-only permissions to roles that can enter AI workflow design.
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT workflow_role.role_id, option_menu.menu_id
FROM sys_role_menu workflow_role
JOIN sys_menu option_menu ON option_menu.perms IN ('callcenter:ai-intent:list', 'callcenter:call-queue:list')
WHERE workflow_role.menu_id = @ai_workflow_menu_id;
