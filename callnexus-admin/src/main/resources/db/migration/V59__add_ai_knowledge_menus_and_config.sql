-- AI 能力菜单和基础配置。

INSERT IGNORE INTO cc_callcenter_config_definition
(id, group_code, group_name, config_key, config_name, value_type, editor_type, default_value, unit, options_json, description, risk_level, sort_order, enabled)
VALUES
(2072000000000000001, 'AI', 'AI 能力', 'ai.knowledgeOssConfigKey', '知识文档OSS配置键', 'STRING', 'INPUT', 'knowledge-document', NULL, NULL, '知识库原始文件使用的OSS配置键。', 'HIGH', 40, 1),
(2072000000000000002, 'AI', 'AI 能力', 'ai.indexWorkerConcurrency', '知识索引并发数', 'INT', 'NUMBER', '2', '个', NULL, '单实例同时执行的知识索引任务数量。', 'MEDIUM', 50, 1),
(2072000000000000003, 'AI', 'AI 能力', 'ai.indexLeaseMinutes', '知识索引租约', 'INT', 'NUMBER', '30', '分钟', NULL, '索引任务超过租约后允许其他实例恢复。', 'MEDIUM', 60, 1),
(2072000000000000004, 'AI', 'AI 能力', 'ai.maxDocumentSizeMb', '知识文档最大大小', 'INT', 'NUMBER', '50', 'MB', NULL, '知识库单文件上传大小限制。', 'LOW', 70, 1),
(2072000000000000005, 'AI', 'AI 能力', 'ai.chatStreamTimeoutSeconds', 'AI对话流超时', 'INT', 'NUMBER', '120', '秒', NULL, '流式AI回答最长等待时间。', 'LOW', 80, 1);

INSERT IGNORE INTO sys_menu VALUES('9320', 'AI能力', '9000', '6', 'callcenter-ai', NULL, '', 1, 0, 'M', '0', '0', '', 'chat-dot-round', 103, 1, SYSDATE(), NULL, NULL, 'AI模型、知识库、助手和语音能力');
INSERT IGNORE INTO sys_menu VALUES('9321', 'AI模型', '9320', '1', 'ai-model', 'callcenter/ai-model/index', '', 1, 0, 'C', '0', '0', 'callcenter:ai-model:list', 'cpu', 103, 1, SYSDATE(), NULL, NULL, 'AI模型服务商和模型配置');
INSERT IGNORE INTO sys_menu VALUES('9322', 'AI模型查询', '9321', '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-model:query', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9323', 'AI模型新增', '9321', '2', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-model:create', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9324', 'AI模型修改', '9321', '3', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-model:update', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9325', 'AI模型删除', '9321', '4', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-model:delete', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9326', 'AI模型测试', '9321', '5', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-model:test', '#', 103, 1, SYSDATE(), NULL, NULL, '');

INSERT IGNORE INTO sys_menu VALUES('9330', '知识库', '9320', '2', 'ai-knowledge', 'callcenter/ai-knowledge/index', '', 1, 0, 'C', '0', '0', 'callcenter:ai-knowledge:list', 'notebook-2', 103, 1, SYSDATE(), NULL, NULL, 'AI知识库、文档和FAQ');
INSERT IGNORE INTO sys_menu VALUES('9331', '知识库查询', '9330', '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-knowledge:query', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9332', '知识库新增', '9330', '2', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-knowledge:create', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9333', '知识库修改', '9330', '3', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-knowledge:update', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9334', '知识库删除', '9330', '4', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-knowledge:delete', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9335', '知识库索引', '9330', '5', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-knowledge:update', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9336', 'FAQ管理', '9330', '6', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-knowledge:list', '#', 103, 1, SYSDATE(), NULL, NULL, '');

INSERT IGNORE INTO sys_menu VALUES('9340', 'AI助手', '9320', '3', 'ai-agent', 'callcenter/ai-agent/index', '', 1, 0, 'C', '0', '0', 'callcenter:ai-agent:list', 'service', 103, 1, SYSDATE(), NULL, NULL, 'AI助手和知识库绑定');
INSERT IGNORE INTO sys_menu VALUES('9341', 'AI助手查询', '9340', '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-agent:query', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9342', 'AI助手新增', '9340', '2', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-agent:create', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9343', 'AI助手修改', '9340', '3', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-agent:update', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9344', 'AI助手删除', '9340', '4', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-agent:delete', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9345', 'AI助手测试', '9340', '5', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-conversation:chat', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9346', 'AI助手对话', '9340', '6', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-conversation:list', '#', 103, 1, SYSDATE(), NULL, NULL, '');

UPDATE sys_menu SET parent_id = '9320', order_num = '4' WHERE menu_id = '9280';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9320' FROM sys_role_menu WHERE menu_id IN ('9000', '9280');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, menu_id FROM (
    SELECT role_id, '9321' menu_id FROM sys_role_menu WHERE menu_id IN ('9000', '9280')
    UNION ALL SELECT role_id, '9322' FROM sys_role_menu WHERE menu_id IN ('9000', '9280')
    UNION ALL SELECT role_id, '9323' FROM sys_role_menu WHERE menu_id IN ('9000', '9280')
    UNION ALL SELECT role_id, '9324' FROM sys_role_menu WHERE menu_id IN ('9000', '9280')
    UNION ALL SELECT role_id, '9325' FROM sys_role_menu WHERE menu_id IN ('9000', '9280')
    UNION ALL SELECT role_id, '9326' FROM sys_role_menu WHERE menu_id IN ('9000', '9280')
    UNION ALL SELECT role_id, '9330' FROM sys_role_menu WHERE menu_id IN ('9000', '9280')
    UNION ALL SELECT role_id, '9331' FROM sys_role_menu WHERE menu_id IN ('9000', '9280')
    UNION ALL SELECT role_id, '9332' FROM sys_role_menu WHERE menu_id IN ('9000', '9280')
    UNION ALL SELECT role_id, '9333' FROM sys_role_menu WHERE menu_id IN ('9000', '9280')
    UNION ALL SELECT role_id, '9334' FROM sys_role_menu WHERE menu_id IN ('9000', '9280')
    UNION ALL SELECT role_id, '9335' FROM sys_role_menu WHERE menu_id IN ('9000', '9280')
    UNION ALL SELECT role_id, '9336' FROM sys_role_menu WHERE menu_id IN ('9000', '9280')
    UNION ALL SELECT role_id, '9340' FROM sys_role_menu WHERE menu_id IN ('9000', '9280')
    UNION ALL SELECT role_id, '9341' FROM sys_role_menu WHERE menu_id IN ('9000', '9280')
    UNION ALL SELECT role_id, '9342' FROM sys_role_menu WHERE menu_id IN ('9000', '9280')
    UNION ALL SELECT role_id, '9343' FROM sys_role_menu WHERE menu_id IN ('9000', '9280')
    UNION ALL SELECT role_id, '9344' FROM sys_role_menu WHERE menu_id IN ('9000', '9280')
    UNION ALL SELECT role_id, '9345' FROM sys_role_menu WHERE menu_id IN ('9000', '9280')
    UNION ALL SELECT role_id, '9346' FROM sys_role_menu WHERE menu_id IN ('9000', '9280')
) permissions;
