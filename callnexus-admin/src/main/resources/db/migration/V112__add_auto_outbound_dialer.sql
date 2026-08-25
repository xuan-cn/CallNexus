-- 自动外呼第四阶段：实际拨号消费、通话关联与结果闭环。

ALTER TABLE cc_auto_outbound_dispatch
    ADD COLUMN attempt_id BIGINT NULL COMMENT '外呼尝试ID' AFTER lease_expires_at,
    ADD COLUMN business_call_id VARCHAR(64) NULL COMMENT '业务通话ID' AFTER attempt_id,
    ADD COLUMN answered_at DATETIME NULL COMMENT '客户接听时间' AFTER started_at,
    ADD COLUMN hangup_cause VARCHAR(64) NULL COMMENT '挂机原因' AFTER completed_at,
    ADD KEY idx_cc_auto_dispatch_call (tenant_id, business_call_id),
    ADD KEY idx_cc_auto_dispatch_attempt (tenant_id, attempt_id);

INSERT INTO sys_config (
    config_id, tenant_id, config_name, config_key, config_value, config_type,
    create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT COALESCE(MAX(config_id), 0) + 1, '000000', '自动外呼单次消费数量',
       'autoOutbound.dialerBatchSize', '20', 'Y', 103, 1, NOW(), 1, NOW(),
       '每次待拨消费器最多领取的调度单数量'
FROM sys_config
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'autoOutbound.dialerBatchSize');

INSERT INTO sys_config (
    config_id, tenant_id, config_name, config_key, config_value, config_type,
    create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT COALESCE(MAX(config_id), 0) + 1, '000000', '自动外呼拨号消费租约秒数',
       'autoOutbound.dialerLeaseSeconds', '90', 'Y', 103, 1, NOW(), 1, NOW(),
       '待拨调度单被消费实例领取后的租约时长'
FROM sys_config
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'autoOutbound.dialerLeaseSeconds');

INSERT INTO sys_config (
    config_id, tenant_id, config_name, config_key, config_value, config_type,
    create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT COALESCE(MAX(config_id), 0) + 1, '000000', '自动外呼通话租约分钟数',
       'autoOutbound.callLeaseMinutes', '120', 'Y', 103, 1, NOW(), 1, NOW(),
       '呼叫提交成功后等待挂机事件闭环的最长保护时间，避免长通话被重复领取'
FROM sys_config
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'autoOutbound.callLeaseMinutes');
