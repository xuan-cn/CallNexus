SET @ai_workflow_menu_id = (
    SELECT menu_id FROM sys_menu WHERE path = 'ai-workflow' AND component = 'callcenter/ai-workflow/index' LIMIT 1
);

SET @permission_id = (
    SELECT CAST(MAX(CAST(menu_id AS DECIMAL(20, 0))) + 1 AS CHAR)
    FROM sys_menu WHERE CAST(menu_id AS CHAR) REGEXP '^[0-9]+$'
);
INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, query_param,
    is_frame, is_cache, menu_type, visible, status, perms, icon,
    create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT @permission_id, 'AI编排测试', @ai_workflow_menu_id, 6, '', '', '',
       1, 0, 'F', '0', '0', 'callcenter:ai-workflow:test', '#',
       103, 1, SYSDATE(), NULL, NULL, '运行草稿测试台'
WHERE @ai_workflow_menu_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'callcenter:ai-workflow:test');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT workflow_role.role_id, option_menu.menu_id
FROM sys_role_menu workflow_role
JOIN sys_menu option_menu ON option_menu.perms IN (
    'callcenter:ai-intent:list',
    'callcenter:call-queue:list',
    'callcenter:ai-agent:list',
    'callcenter:ai-workflow:test'
)
WHERE workflow_role.menu_id = @ai_workflow_menu_id;
