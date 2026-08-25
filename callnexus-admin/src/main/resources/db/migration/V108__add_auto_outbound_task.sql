-- 自动外呼第一阶段：任务策略、多个呼叫时段、按结果重试规则和独立菜单。

ALTER TABLE cc_outbound_task
    MODIFY COLUMN task_type VARCHAR(16) NOT NULL DEFAULT 'PREVIEW' COMMENT '任务类型：PREVIEW预览外呼、AUTO自动外呼',
    ADD COLUMN dial_mode VARCHAR(24) NULL COMMENT '自动拨打模式：AGENTLESS_AI、AGENTLESS_IVR、PROGRESSIVE' AFTER caller_number_id,
    ADD COLUMN target_type VARCHAR(24) NULL COMMENT '接听目标类型：AI_AGENT、IVR_FLOW、SKILL_GROUP' AFTER dial_mode,
    ADD COLUMN target_id BIGINT NULL COMMENT '接听目标ID' AFTER target_type,
    ADD COLUMN skill_group_id BIGINT NULL COMMENT '转人工或渐进外呼使用的技能组ID' AFTER target_id,
    ADD COLUMN concurrency_limit INT NULL COMMENT '任务最大并发数' AFTER skill_group_id,
    ADD COLUMN calls_per_minute INT NULL COMMENT '任务每分钟最大呼叫数' AFTER concurrency_limit,
    ADD COLUMN max_calls_per_day INT NULL COMMENT '单个客户每日最大呼叫次数' AFTER calls_per_minute,
    ADD COLUMN max_calls_total INT NULL COMMENT '单个客户在任务内最大呼叫次数' AFTER max_calls_per_day,
    ADD COLUMN min_call_interval_minutes INT NULL COMMENT '同一客户最小呼叫间隔分钟数' AFTER max_calls_total,
    ADD COLUMN schedule_timezone VARCHAR(64) NULL COMMENT '任务调度时区' AFTER min_call_interval_minutes,
    ADD KEY idx_cc_outbound_task_type_status (tenant_id, task_type, status),
    ADD KEY idx_cc_outbound_task_target (tenant_id, target_type, target_id);

CREATE TABLE cc_outbound_task_call_window (
    id BIGINT NOT NULL COMMENT '呼叫时段ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    task_id BIGINT NOT NULL COMMENT '外呼任务ID',
    weekdays VARCHAR(32) NOT NULL COMMENT '星期集合，1到7，逗号分隔',
    start_time TIME NOT NULL COMMENT '开始时间',
    end_time TIME NOT NULL COMMENT '结束时间',
    sort_order INT NOT NULL DEFAULT 1 COMMENT '排序',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_cc_outbound_window_task (tenant_id, task_id, enabled, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自动外呼任务呼叫时段';

CREATE TABLE cc_outbound_task_retry_rule (
    id BIGINT NOT NULL COMMENT '重试规则ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    task_id BIGINT NOT NULL COMMENT '外呼任务ID',
    result_code VARCHAR(32) NOT NULL COMMENT '呼叫结果编码',
    retry_enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否重试',
    max_retry_count INT NOT NULL DEFAULT 1 COMMENT '最大重试次数',
    retry_interval_minutes INT NOT NULL DEFAULT 30 COMMENT '重试间隔分钟数',
    sort_order INT NOT NULL DEFAULT 1 COMMENT '排序',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_outbound_retry_rule (tenant_id, task_id, result_code, deleted),
    KEY idx_cc_outbound_retry_task (tenant_id, task_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自动外呼任务重试规则';

-- 现有页面明确为预览外呼，新页面与其共享外呼领域模型。
UPDATE sys_menu
SET menu_name = '预览外呼', order_num = 1, remark = '坐席领取名单后人工确认拨打'
WHERE menu_id = '9150';

INSERT INTO sys_menu VALUES(
    '2091400000000000001', '自动外呼', '9800', '2', 'auto-outbound-task',
    'callcenter/auto-outbound-task/index', '', 1, 0, 'C', '0', '0',
    'callcenter:auto-outbound-task:list', 'phone', 103, 1, SYSDATE(), NULL, NULL, '自动外呼任务与调度策略'
);
INSERT INTO sys_menu VALUES('2091400000000000002', '自动外呼查询', '2091400000000000001', '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:auto-outbound-task:query', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT INTO sys_menu VALUES('2091400000000000003', '自动外呼新增', '2091400000000000001', '2', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:auto-outbound-task:create', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT INTO sys_menu VALUES('2091400000000000004', '自动外呼修改', '2091400000000000001', '3', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:auto-outbound-task:update', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT INTO sys_menu VALUES('2091400000000000005', '自动外呼删除', '2091400000000000001', '4', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:auto-outbound-task:delete', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT INTO sys_menu VALUES('2091400000000000006', '自动外呼执行', '2091400000000000001', '5', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:auto-outbound-task:execute', '#', 103, 1, SYSDATE(), NULL, NULL, '');

UPDATE sys_menu SET order_num = order_num + 1
WHERE parent_id = '9800' AND menu_id NOT IN ('9150', '2091400000000000001') AND order_num >= 2;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '2091400000000000001' FROM sys_role_menu WHERE menu_id = '9150';
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '2091400000000000002' FROM sys_role_menu WHERE menu_id = '9151';
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '2091400000000000003' FROM sys_role_menu WHERE menu_id = '9152';
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '2091400000000000004' FROM sys_role_menu WHERE menu_id = '9153';
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '2091400000000000005' FROM sys_role_menu WHERE menu_id = '9154';
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '2091400000000000006' FROM sys_role_menu WHERE menu_id = '9155';
