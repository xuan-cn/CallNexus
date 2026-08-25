-- 自动外呼第三阶段：注册调度器可配置参数。

INSERT IGNORE INTO cc_callcenter_config_definition
(id, group_code, group_name, config_key, config_name, value_type, editor_type, default_value, unit,
 options_json, description, risk_level, sort_order, enabled)
VALUES
(2099000000000000001, 'AUTO_OUTBOUND', '自动外呼', 'autoOutbound.taskLeaseSeconds',
 '任务调度租约时长', 'INT', 'NUMBER', '15', '秒', NULL,
 '多实例调度时单个任务的数据库租约时长。', 'MEDIUM', 10, 1),
(2099000000000000002, 'AUTO_OUTBOUND', '自动外呼', 'autoOutbound.dispatchLeaseMinutes',
 '待拨调度单租约时长', 'INT', 'NUMBER', '10', '分钟', NULL,
 '调度单被消费实例领取后的处理租约时长，超时后允许恢复。', 'MEDIUM', 20, 1),
(2099000000000000003, 'AUTO_OUTBOUND', '自动外呼', 'autoOutbound.tenantConcurrencyLimit',
 '租户自动外呼并发上限', 'INT', 'NUMBER', '100', '路', NULL,
 '单个租户所有自动外呼任务共享的并发上限。', 'HIGH', 30, 1),
(2099000000000000004, 'AUTO_OUTBOUND', '自动外呼', 'autoOutbound.nodeConcurrencyLimit',
 'FreeSWITCH 节点并发上限', 'INT', 'NUMBER', '100', '路', NULL,
 '单个 FreeSWITCH 节点允许的自动外呼并发上限。', 'HIGH', 40, 1),
(2099000000000000005, 'AUTO_OUTBOUND', '自动外呼', 'autoOutbound.callerConcurrencyLimit',
 '主叫号码并发上限', 'INT', 'NUMBER', '20', '路', NULL,
 '单个主叫号码同时允许的自动外呼数量。', 'HIGH', 50, 1);
