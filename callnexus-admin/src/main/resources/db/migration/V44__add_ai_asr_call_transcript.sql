-- AI 语音 ASR 通话录音转写
-- 记录通话录音的转写主结果和句子分段，第一版使用阿里云 NLS 文件转写。

CREATE TABLE IF NOT EXISTS cc_ai_call_transcript (
    id                  BIGINT          NOT NULL COMMENT '通话转写ID',
    tenant_id           VARCHAR(20)     NOT NULL COMMENT '租户ID',
    call_session_id     BIGINT          NOT NULL COMMENT '通话会话ID',
    business_call_id    VARCHAR(64)     NOT NULL COMMENT '业务通话ID',
    provider_id         BIGINT          NULL COMMENT 'ASR服务商配置ID',
    provider_type       VARCHAR(32)     NULL COMMENT 'ASR服务商类型',
    input_media_id      BIGINT          NULL COMMENT '输入录音媒体ID',
    recording_oss_id    BIGINT          NULL COMMENT '输入录音OSS文件ID',
    status              VARCHAR(32)     NOT NULL COMMENT '转写状态：PROCESSING处理中、SUCCESS成功、FAILED失败',
    full_text           TEXT            NULL COMMENT '完整转写文本',
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
    UNIQUE KEY uk_cc_ai_call_transcript_session (tenant_id, call_session_id, deleted),
    KEY idx_cc_ai_call_transcript_business (tenant_id, business_call_id),
    KEY idx_cc_ai_call_transcript_status (tenant_id, status, create_time)
) ENGINE=InnoDB COMMENT='AI通话录音转写表';

CREATE TABLE IF NOT EXISTS cc_ai_call_transcript_segment (
    id                  BIGINT          NOT NULL COMMENT '通话转写分段ID',
    tenant_id           VARCHAR(20)     NOT NULL COMMENT '租户ID',
    transcript_id       BIGINT          NOT NULL COMMENT '通话转写ID',
    call_session_id     BIGINT          NOT NULL COMMENT '通话会话ID',
    business_call_id    VARCHAR(64)     NOT NULL COMMENT '业务通话ID',
    speaker             VARCHAR(32)     NOT NULL DEFAULT 'UNKNOWN' COMMENT '说话人：CUSTOMER客户、AGENT坐席、UNKNOWN未知',
    sentence_index      INT             NULL COMMENT '句子序号',
    start_ms            INT             NULL COMMENT '开始毫秒',
    end_ms              INT             NULL COMMENT '结束毫秒',
    text_content        VARCHAR(2000)   NOT NULL COMMENT '分段文本',
    final_result        TINYINT         NOT NULL DEFAULT 1 COMMENT '是否最终结果',
    confidence          DECIMAL(8,4)    NULL COMMENT '置信度',
    create_dept         BIGINT          NULL COMMENT '创建部门',
    create_by           BIGINT          NULL COMMENT '创建人',
    create_time         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by           BIGINT          NULL COMMENT '更新人',
    update_time         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version             INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted             TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    KEY idx_cc_ai_call_transcript_segment_transcript (tenant_id, transcript_id, sentence_index),
    KEY idx_cc_ai_call_transcript_segment_session (tenant_id, call_session_id)
) ENGINE=InnoDB COMMENT='AI通话录音转写分段表';

INSERT IGNORE INTO sys_menu VALUES('9286', 'AI语音转写', '9280', '6', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:ai-speech:transcribe', '#', 103, 1, SYSDATE(), NULL, NULL, '');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9286'
FROM sys_role_menu
WHERE menu_id IN ('9202', '9100', '9280');
