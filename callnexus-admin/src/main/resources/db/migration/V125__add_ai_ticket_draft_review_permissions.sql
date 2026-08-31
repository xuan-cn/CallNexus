-- AI ticket draft review is presented as a tab of the existing ticket page.
SET @ticket_menu_id = COALESCE(
    (SELECT menu_id FROM sys_menu WHERE component = 'callcenter/ticket/index' AND menu_type = 'C' LIMIT 1),
    (SELECT menu_id FROM sys_menu WHERE path = 'ticket' AND menu_type = 'C' LIMIT 1)
);

SET @next_menu_id = (SELECT CAST(COALESCE(MAX(CAST(menu_id AS DECIMAL(20, 0))), 9630) + 1 AS CHAR) FROM sys_menu WHERE CAST(menu_id AS CHAR) REGEXP '^[0-9]+$');
SET @draft_list_id = COALESCE((SELECT menu_id FROM sys_menu WHERE perms = 'callcenter:ai-ticket-draft:list' LIMIT 1), @next_menu_id);
INSERT INTO sys_menu SELECT @draft_list_id, 'AI工单草稿列表', @ticket_menu_id, '10', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-ticket-draft:list', '#', 103, 1, SYSDATE(), NULL, NULL, ''
WHERE @ticket_menu_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'callcenter:ai-ticket-draft:list');

SET @query_id = (SELECT CAST(MAX(CAST(menu_id AS DECIMAL(20, 0))) + 1 AS CHAR) FROM sys_menu WHERE CAST(menu_id AS CHAR) REGEXP '^[0-9]+$');
INSERT INTO sys_menu SELECT @query_id, 'AI工单草稿查询', @ticket_menu_id, '11', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-ticket-draft:query', '#', 103, 1, SYSDATE(), NULL, NULL, '' WHERE @ticket_menu_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'callcenter:ai-ticket-draft:query');
SET @edit_id = (SELECT CAST(MAX(CAST(menu_id AS DECIMAL(20, 0))) + 1 AS CHAR) FROM sys_menu WHERE CAST(menu_id AS CHAR) REGEXP '^[0-9]+$');
INSERT INTO sys_menu SELECT @edit_id, 'AI工单草稿修改', @ticket_menu_id, '12', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-ticket-draft:edit', '#', 103, 1, SYSDATE(), NULL, NULL, '' WHERE @ticket_menu_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'callcenter:ai-ticket-draft:edit');
SET @review_id = (SELECT CAST(MAX(CAST(menu_id AS DECIMAL(20, 0))) + 1 AS CHAR) FROM sys_menu WHERE CAST(menu_id AS CHAR) REGEXP '^[0-9]+$');
INSERT INTO sys_menu SELECT @review_id, 'AI工单草稿审核', @ticket_menu_id, '13', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-ticket-draft:review', '#', 103, 1, SYSDATE(), NULL, NULL, '' WHERE @ticket_menu_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'callcenter:ai-ticket-draft:review');
SET @regenerate_id = (SELECT CAST(MAX(CAST(menu_id AS DECIMAL(20, 0))) + 1 AS CHAR) FROM sys_menu WHERE CAST(menu_id AS CHAR) REGEXP '^[0-9]+$');
INSERT INTO sys_menu SELECT @regenerate_id, 'AI工单草稿重新生成', @ticket_menu_id, '14', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-ticket-draft:regenerate', '#', 103, 1, SYSDATE(), NULL, NULL, '' WHERE @ticket_menu_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'callcenter:ai-ticket-draft:regenerate');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT source.role_id, permission.menu_id
FROM sys_role_menu source
JOIN sys_menu permission ON permission.perms LIKE 'callcenter:ai-ticket-draft:%'
WHERE source.menu_id = @ticket_menu_id;
