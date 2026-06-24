-- AI 语音 TTS 适配层
-- 提供通用 HTTP TTS Provider、语音模板、生成任务和生成媒体绑定能力。

CREATE TABLE IF NOT EXISTS cc_ai_tts_provider (
    id                  BIGINT          NOT NULL COMMENT 'TTS服务商配置ID',
    tenant_id           VARCHAR(20)     NOT NULL COMMENT '租户ID',
    provider_code       VARCHAR(64)     NOT NULL COMMENT '服务商编码',
    provider_name       VARCHAR(128)    NOT NULL COMMENT '服务商名称',
    provider_type       VARCHAR(32)     NOT NULL COMMENT '服务商类型：CUSTOM_HTTP通用HTTP、ALIYUN_DASHSCOPE阿里云百炼',
    endpoint_url        VARCHAR(512)    NOT NULL COMMENT '请求地址',
    http_method         VARCHAR(16)     NOT NULL DEFAULT 'POST' COMMENT '请求方法',
    auth_type           VARCHAR(32)     NOT NULL DEFAULT 'NONE' COMMENT '认证方式：NONE无、BEARER Bearer Token、HEADER Header Token',
    auth_header_name    VARCHAR(128)    NULL COMMENT '认证Header名称',
    auth_token          VARCHAR(1024)   NULL COMMENT '认证Token',
    default_voice       VARCHAR(128)    NULL COMMENT '默认音色',
    default_format      VARCHAR(16)     NOT NULL DEFAULT 'wav' COMMENT '默认音频格式',
    default_sample_rate INT             NOT NULL DEFAULT 8000 COMMENT '默认采样率',
    timeout_seconds     INT             NOT NULL DEFAULT 30 COMMENT '超时时间，单位秒',
    enabled             TINYINT         NOT NULL DEFAULT 1 COMMENT '是否启用',
    remark              VARCHAR(500)    NULL COMMENT '备注',
    create_dept         BIGINT          NULL COMMENT '创建部门',
    create_by           BIGINT          NULL COMMENT '创建人',
    create_time         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by           BIGINT          NULL COMMENT '更新人',
    update_time         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version             INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted             TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_ai_tts_provider_code (tenant_id, provider_code, deleted),
    KEY idx_cc_ai_tts_provider_enabled (tenant_id, enabled)
) ENGINE=InnoDB COMMENT='AI语音TTS服务商配置表';

CREATE TABLE IF NOT EXISTS cc_ai_speech_template (
    id                  BIGINT          NOT NULL COMMENT '语音模板ID',
    tenant_id           VARCHAR(20)     NOT NULL COMMENT '租户ID',
    template_code       VARCHAR(64)     NOT NULL COMMENT '模板编码',
    template_name       VARCHAR(128)    NOT NULL COMMENT '模板名称',
    business_type       VARCHAR(64)     NOT NULL COMMENT '业务类型',
    template_text       VARCHAR(1000)   NOT NULL COMMENT '模板内容',
    default_voice       VARCHAR(128)    NULL COMMENT '默认音色',
    enabled             TINYINT         NOT NULL DEFAULT 1 COMMENT '是否启用',
    remark              VARCHAR(500)    NULL COMMENT '备注',
    create_dept         BIGINT          NULL COMMENT '创建部门',
    create_by           BIGINT          NULL COMMENT '创建人',
    create_time         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by           BIGINT          NULL COMMENT '更新人',
    update_time         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version             INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted             TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_ai_speech_template_code (tenant_id, template_code, deleted),
    KEY idx_cc_ai_speech_template_business (tenant_id, business_type, enabled)
) ENGINE=InnoDB COMMENT='AI语音模板表';

CREATE TABLE IF NOT EXISTS cc_ai_speech_task (
    id                  BIGINT          NOT NULL COMMENT '语音生成任务ID',
    tenant_id           VARCHAR(20)     NOT NULL COMMENT '租户ID',
    task_type           VARCHAR(32)     NOT NULL COMMENT '任务类型：TTS语音合成、ASR语音转写',
    business_type       VARCHAR(64)     NOT NULL COMMENT '业务类型',
    business_id         BIGINT          NULL COMMENT '业务对象ID',
    provider_id         BIGINT          NULL COMMENT 'TTS服务商配置ID',
    provider_type       VARCHAR(32)     NULL COMMENT '服务商类型',
    voice_name          VARCHAR(128)    NULL COMMENT '音色名称',
    text_content        VARCHAR(2000)   NULL COMMENT '输入文本',
    input_media_id      BIGINT          NULL COMMENT '输入媒体ID',
    output_media_id     BIGINT          NULL COMMENT '输出媒体ID',
    status              VARCHAR(32)     NOT NULL COMMENT '任务状态：PROCESSING处理中、SUCCESS成功、FAILED失败',
    retry_count         INT             NOT NULL DEFAULT 0 COMMENT '重试次数',
    failure_reason      VARCHAR(1000)   NULL COMMENT '失败原因',
    started_at          DATETIME        NULL COMMENT '开始时间',
    finished_at         DATETIME        NULL COMMENT '完成时间',
    create_dept         BIGINT          NULL COMMENT '创建部门',
    create_by           BIGINT          NULL COMMENT '创建人',
    create_time         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by           BIGINT          NULL COMMENT '更新人',
    update_time         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version             INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted             TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    KEY idx_cc_ai_speech_task_business (tenant_id, business_type, business_id, status),
    KEY idx_cc_ai_speech_task_provider (tenant_id, provider_id, create_time)
) ENGINE=InnoDB COMMENT='AI语音生成任务表';

CREATE TABLE IF NOT EXISTS cc_ai_generated_media (
    id                  BIGINT          NOT NULL COMMENT 'AI生成媒体绑定ID',
    tenant_id           VARCHAR(20)     NOT NULL COMMENT '租户ID',
    business_type       VARCHAR(64)     NOT NULL COMMENT '业务类型',
    business_id         BIGINT          NOT NULL COMMENT '业务对象ID',
    media_id            BIGINT          NULL COMMENT '媒体资产ID',
    task_id             BIGINT          NULL COMMENT '生成任务ID',
    text_hash           VARCHAR(128)    NULL COMMENT '文本哈希',
    generation_status   VARCHAR(32)     NOT NULL COMMENT '生成状态：PROCESSING处理中、SUCCESS成功、FAILED失败',
    generated_at        DATETIME        NULL COMMENT '生成时间',
    failure_reason      VARCHAR(1000)   NULL COMMENT '失败原因',
    create_dept         BIGINT          NULL COMMENT '创建部门',
    create_by           BIGINT          NULL COMMENT '创建人',
    create_time         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by           BIGINT          NULL COMMENT '更新人',
    update_time         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version             INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted             TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_ai_generated_media_business (tenant_id, business_type, business_id, deleted),
    KEY idx_cc_ai_generated_media_media (tenant_id, media_id)
) ENGINE=InnoDB COMMENT='AI生成媒体绑定表';

INSERT IGNORE INTO cc_ai_speech_template (
    id, tenant_id, template_code, template_name, business_type, template_text,
    default_voice, enabled, remark, create_dept, create_by, create_time, update_by, update_time, version, deleted
) VALUES (
    2069000000000000001, '000000', 'AGENT_NUMBER_PROMPT_DEFAULT', '坐席工号播报默认模板',
    'AGENT_NUMBER_PROMPT', '工号{extension}为您服务', 'default', 1,
    '队列接通后向客户播报坐席工号使用', 103, 1, SYSDATE(), NULL, NULL, 0, 0
);

INSERT IGNORE INTO sys_menu VALUES('9280', 'AI语音配置', '9202', '3', 'ai-speech', 'callcenter/ai-speech/index', '', 1, 0, 'C', '0', '0', 'callcenter:ai-speech:list', 'sound', 103, 1, SYSDATE(), NULL, NULL, 'TTS服务商、语音模板和生成任务');
INSERT IGNORE INTO sys_menu VALUES('9281', 'AI语音查询', '9280', '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-speech:list', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9282', 'AI语音新增', '9280', '2', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-speech:create', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9283', 'AI语音修改', '9280', '3', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-speech:update', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9284', 'AI语音删除', '9280', '4', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-speech:delete', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9285', 'AI语音测试', '9280', '5', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-speech:test', '#', 103, 1, SYSDATE(), NULL, NULL, '');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9280'
FROM sys_role_menu
WHERE menu_id IN ('9202', '9100');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, menu_id
FROM (
    SELECT role_id, '9281' AS menu_id FROM sys_role_menu WHERE menu_id IN ('9202', '9100', '9280')
    UNION ALL
    SELECT role_id, '9282' AS menu_id FROM sys_role_menu WHERE menu_id IN ('9202', '9100', '9280')
    UNION ALL
    SELECT role_id, '9283' AS menu_id FROM sys_role_menu WHERE menu_id IN ('9202', '9100', '9280')
    UNION ALL
    SELECT role_id, '9284' AS menu_id FROM sys_role_menu WHERE menu_id IN ('9202', '9100', '9280')
    UNION ALL
    SELECT role_id, '9285' AS menu_id FROM sys_role_menu WHERE menu_id IN ('9202', '9100', '9280')
) permission_source;
