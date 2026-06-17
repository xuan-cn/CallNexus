CREATE TABLE cc_callcenter_config_definition (
    id BIGINT NOT NULL COMMENT '配置定义ID',
    group_code VARCHAR(64) NOT NULL COMMENT '分组编码',
    group_name VARCHAR(64) NOT NULL COMMENT '分组名称',
    config_key VARCHAR(128) NOT NULL COMMENT '配置键',
    config_name VARCHAR(128) NOT NULL COMMENT '配置名称',
    value_type VARCHAR(32) NOT NULL COMMENT '值类型：STRING字符串、INT整数、BOOLEAN布尔、SELECT下拉',
    editor_type VARCHAR(32) NOT NULL COMMENT '前端控件类型：INPUT、NUMBER、SWITCH、SELECT',
    default_value VARCHAR(500) NULL COMMENT '系统默认值',
    unit VARCHAR(32) NULL COMMENT '单位',
    options_json VARCHAR(1000) NULL COMMENT '选项JSON',
    description VARCHAR(500) NULL COMMENT '配置说明',
    risk_level VARCHAR(16) NOT NULL DEFAULT 'LOW' COMMENT '风险等级：LOW低、MEDIUM中、HIGH高',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_config_definition_key (config_key),
    KEY idx_cc_config_definition_group (group_code, sort_order)
) ENGINE=InnoDB COMMENT='呼叫中心配置定义';

CREATE TABLE cc_callcenter_config_value (
    id BIGINT NOT NULL COMMENT '租户配置值ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    config_key VARCHAR(128) NOT NULL COMMENT '配置键',
    config_value VARCHAR(500) NULL COMMENT '配置值',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_config_value_tenant_key (tenant_id, config_key, deleted),
    KEY idx_cc_config_value_key (config_key)
) ENGINE=InnoDB COMMENT='呼叫中心租户配置值';

INSERT INTO cc_callcenter_config_definition
(id, group_code, group_name, config_key, config_name, value_type, editor_type, default_value, unit, options_json, description, risk_level, sort_order, enabled)
VALUES
('2066700000000000001', 'OUTBOUND', '外呼配置', 'outbound.claimLeaseMinutes', '名单领取租约时长', 'INT', 'NUMBER', '15', '分钟', NULL, '坐席领取名单后超过该时间未操作，名单可被异常恢复。', 'MEDIUM', 10, 1),
('2066700000000000002', 'OUTBOUND', '外呼配置', 'outbound.dialingLeaseMinutes', '拨打中租约时长', 'INT', 'NUMBER', '120', '分钟', NULL, '名单进入拨打中后超过该时间仍未结束，会被异常恢复。', 'MEDIUM', 20, 1),
('2066700000000000003', 'OUTBOUND', '外呼配置', 'outbound.dueRetryBatchSize', '到期重呼批量大小', 'INT', 'NUMBER', '100', '条', NULL, '调度器单次扫描到期重呼名单的最大处理数量。', 'LOW', 30, 1),
('2066700000000000004', 'OUTBOUND', '外呼配置', 'outbound.autoClaimAfterSave', '保存结果后自动领取下一条', 'BOOLEAN', 'SWITCH', 'false', NULL, NULL, '预览式外呼建议关闭，避免坐席未确认结果就进入下一条。', 'LOW', 40, 1),
('2066700000000000011', 'AGENT', '坐席配置', 'agent.defaultAfterCallSeconds', '默认话后整理时长', 'INT', 'NUMBER', '0', '秒', NULL, '队列或业务未配置话后整理时使用该默认值。', 'LOW', 10, 1),
('2066700000000000012', 'AGENT', '坐席配置', 'agent.busyReminderMinutes', '示忙持续提醒时长', 'INT', 'NUMBER', '30', '分钟', NULL, '坐席长时间示忙时用于后续提醒。', 'LOW', 20, 1),
('2066700000000000013', 'AGENT', '坐席配置', 'agent.incomingAlertMode', '来电提醒方式', 'SELECT', 'SELECT', 'POPUP_SOUND_SHAKE', NULL, '[{\"label\":\"弹窗+声音+抖动\",\"value\":\"POPUP_SOUND_SHAKE\"},{\"label\":\"仅弹窗\",\"value\":\"POPUP\"},{\"label\":\"弹窗+声音\",\"value\":\"POPUP_SOUND\"}]', '浏览器软电话收到来电时的默认提醒方式。', 'LOW', 30, 1),
('2066700000000000021', 'QUEUE', '队列配置', 'queue.defaultMaxWaitSeconds', '默认最大等待时间', 'INT', 'NUMBER', '300', '秒', NULL, '新建队列时可使用的最大等待时间默认值。', 'LOW', 10, 1),
('2066700000000000022', 'QUEUE', '队列配置', 'queue.defaultRingTimeoutSeconds', '默认振铃超时', 'INT', 'NUMBER', '20', '秒', NULL, '新建队列时坐席单次振铃超时默认值。', 'LOW', 20, 1),
('2066700000000000023', 'QUEUE', '队列配置', 'queue.defaultMaxNoAnswer', '默认最大未接次数', 'INT', 'NUMBER', '3', '次', NULL, '新建队列时坐席最大未接次数默认值。', 'LOW', 30, 1),
('2066700000000000024', 'QUEUE', '队列配置', 'queue.syncFailureBlocksRoute', '队列同步失败阻止路由选择', 'BOOLEAN', 'SWITCH', 'true', NULL, NULL, '开启后，未同步成功的队列不能被号码或 IVR 选择。', 'MEDIUM', 40, 1),
('2066700000000000031', 'VOICEMAIL', '语音留言', 'voicemail.defaultMaxSeconds', '默认最长录制秒数', 'INT', 'NUMBER', '120', '秒', NULL, '新建留言箱时默认带入的最长录制时间。', 'LOW', 10, 1),
('2066700000000000032', 'VOICEMAIL', '语音留言', 'voicemail.defaultSilenceThreshold', '默认静音阈值', 'INT', 'NUMBER', '200', NULL, NULL, '新建留言箱时默认带入的 FreeSWITCH 静音检测阈值。', 'LOW', 20, 1),
('2066700000000000033', 'VOICEMAIL', '语音留言', 'voicemail.defaultSilenceHits', '默认静音次数', 'INT', 'NUMBER', '5', '次', NULL, '新建留言箱时默认带入的连续静音次数。', 'LOW', 30, 1),
('2066700000000000034', 'VOICEMAIL', '语音留言', 'voicemail.unhandledReminderMinutes', '未处理提醒阈值', 'INT', 'NUMBER', '30', '分钟', NULL, '留言超过该时间仍未处理时，后续提醒中心可标记为超时。', 'LOW', 40, 1),
('2066700000000000041', 'BUSINESS_HOURS', '工作时间', 'businessHours.defaultTimezone', '默认时区', 'STRING', 'INPUT', 'Asia/Shanghai', NULL, NULL, '新建工作时间方案时默认使用的 IANA 时区。', 'LOW', 10, 1),
('2066700000000000042', 'BUSINESS_HOURS', '工作时间', 'businessHours.allowExceptionOverride', '允许特殊日期覆盖周时段', 'BOOLEAN', 'SWITCH', 'true', NULL, NULL, '关闭后后续可禁止特殊日期覆盖普通周时段。', 'LOW', 20, 1),
('2066700000000000051', 'MEDIA', '录音与媒体', 'media.callRecordingConfigKey', '通话录音 OSS 配置键', 'STRING', 'INPUT', 'call-recording', NULL, NULL, '通话录音默认使用的 OSS 配置键。', 'HIGH', 10, 1),
('2066700000000000052', 'MEDIA', '录音与媒体', 'media.voicemailRecordingConfigKey', '语音留言 OSS 配置键', 'STRING', 'INPUT', 'voicemail-recording', NULL, NULL, '语音留言录音默认使用的 OSS 配置键。', 'HIGH', 20, 1),
('2066700000000000053', 'MEDIA', '录音与媒体', 'media.ivrPromptConfigKey', 'IVR 提示音 OSS 配置键', 'STRING', 'INPUT', 'ivr-prompt', NULL, NULL, 'IVR 提示音默认使用的 OSS 配置键。', 'HIGH', 30, 1),
('2066700000000000054', 'MEDIA', '录音与媒体', 'media.queueWaitMusicConfigKey', '队列等待音 OSS 配置键', 'STRING', 'INPUT', 'queue-wait-music', NULL, NULL, '队列等待音默认使用的 OSS 配置键。', 'HIGH', 40, 1),
('2066700000000000061', 'FREESWITCH', 'FreeSWITCH', 'freeswitch.eslCommandTimeoutSeconds', 'ESL 命令超时', 'INT', 'NUMBER', '10', '秒', NULL, '调用 FreeSWITCH ESL 命令的默认等待时间。', 'MEDIUM', 10, 1),
('2066700000000000062', 'FREESWITCH', 'FreeSWITCH', 'freeswitch.gatewayRefreshWaitMillis', '网关刷新等待时间', 'INT', 'NUMBER', '1500', '毫秒', NULL, '执行 killgw 后等待 FreeSWITCH 释放旧网关的时间。', 'MEDIUM', 20, 1),
('2066700000000000063', 'FREESWITCH', 'FreeSWITCH', 'freeswitch.logDialplanNotFoundDetail', '记录 Dialplan 未命中详情', 'BOOLEAN', 'SWITCH', 'true', NULL, NULL, '开启后动态 Dialplan 未命中时记录关键请求参数，便于排障。', 'LOW', 30, 1),
('2066700000000000071', 'AI', 'AI 能力', 'ai.voiceTranscriptionEnabled', '启用语音转写', 'BOOLEAN', 'SWITCH', 'false', NULL, NULL, '预留开关，后续接入语音留言和通话录音转写。', 'LOW', 10, 1),
('2066700000000000072', 'AI', 'AI 能力', 'ai.callSummaryEnabled', '启用通话摘要', 'BOOLEAN', 'SWITCH', 'false', NULL, NULL, '预留开关，后续接入通话摘要和坐席辅助。', 'LOW', 20, 1),
('2066700000000000073', 'AI', 'AI 能力', 'ai.provider', 'AI 服务商', 'SELECT', 'SELECT', 'NONE', NULL, '[{\"label\":\"暂不启用\",\"value\":\"NONE\"},{\"label\":\"OpenAI兼容接口\",\"value\":\"OPENAI_COMPATIBLE\"},{\"label\":\"本地模型\",\"value\":\"LOCAL\"}]', 'AI 能力使用的服务商类型，第一版仅预留。', 'LOW', 30, 1);

INSERT INTO sys_menu VALUES('9270', '配置中心', '9000', '27', 'callcenter-config', 'callcenter/callcenter-config/index', '', 1, 0, 'C', '0', '0', 'callcenter:config:list', 'setting', 103, 1, sysdate(), null, null, '呼叫中心配置中心菜单');
INSERT INTO sys_menu VALUES('9271', '配置中心查询', '9270', '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:config:query', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9272', '配置中心修改', '9270', '2', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:config:update', '#', 103, 1, sysdate(), null, null, '');
