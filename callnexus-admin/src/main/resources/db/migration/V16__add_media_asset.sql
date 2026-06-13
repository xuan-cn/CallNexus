CREATE TABLE cc_media_asset (
    id                  BIGINT          NOT NULL COMMENT '媒体资产ID',
    tenant_id           VARCHAR(20)     NOT NULL COMMENT '租户ID',
    asset_name          VARCHAR(128)    NOT NULL COMMENT '媒体名称',
    category            VARCHAR(32)     NOT NULL COMMENT '媒体分类：IVR提示音、队列等待音乐、振铃音、用户音乐、通话录音',
    source_type         VARCHAR(32)     NOT NULL DEFAULT 'UPLOAD' COMMENT '来源类型：人工上传、TTS生成、通话录音、AI生成',
    oss_id              BIGINT          NOT NULL COMMENT '关联sys_oss文件ID',
    original_file_name  VARCHAR(255)    NULL COMMENT '原始文件名',
    content_type        VARCHAR(128)    NULL COMMENT 'MIME类型',
    file_suffix         VARCHAR(32)     NULL COMMENT '文件扩展名',
    file_size           BIGINT          NULL COMMENT '文件大小（字节）',
    duration_ms         BIGINT          NULL COMMENT '音频时长（毫秒）',
    sample_rate         INT             NULL COMMENT '采样率',
    channels            INT             NULL COMMENT '声道数',
    codec               VARCHAR(32)     NULL COMMENT '音频编码',
    language_code       VARCHAR(16)     NULL COMMENT '语言代码，例如zh-CN',
    enabled             TINYINT         NOT NULL DEFAULT 1 COMMENT '是否启用',
    reference_count     INT             NOT NULL DEFAULT 0 COMMENT '业务引用次数',
    transcript_status   VARCHAR(16)     NOT NULL DEFAULT 'NONE' COMMENT '转写状态：未处理、待处理、处理中、成功、失败',
    transcript_text     LONGTEXT        NULL COMMENT '语音转写纯文本',
    transcript_oss_id   BIGINT          NULL COMMENT '带时间戳转写JSON文件OSS ID',
    summary_text        TEXT            NULL COMMENT 'AI生成摘要',
    keywords_json       JSON            NULL COMMENT 'AI提取关键词JSON',
    sentiment_json      JSON            NULL COMMENT 'AI情绪分析结果JSON',
    ai_metadata_json    JSON            NULL COMMENT 'AI厂商、模型、置信度和版本等元数据',
    voice_provider      VARCHAR(32)     NULL COMMENT 'TTS语音厂商',
    voice_model         VARCHAR(64)     NULL COMMENT 'TTS语音模型',
    voice_name          VARCHAR(64)     NULL COMMENT 'TTS音色名称',
    source_text         LONGTEXT        NULL COMMENT 'TTS原始文本',
    remark              VARCHAR(500)    NULL COMMENT '备注',
    create_dept         BIGINT          NULL COMMENT '创建部门',
    create_by           BIGINT          NULL COMMENT '创建人',
    create_time         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by           BIGINT          NULL COMMENT '更新人',
    update_time         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version             INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted             TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    KEY idx_cc_media_asset_category (tenant_id, category, enabled),
    KEY idx_cc_media_asset_oss (tenant_id, oss_id),
    KEY idx_cc_media_asset_source (tenant_id, source_type),
    KEY idx_cc_media_asset_transcript (tenant_id, transcript_status)
) ENGINE=InnoDB COMMENT='呼叫中心声音媒体资产';

ALTER TABLE cc_call_session
    ADD COLUMN recording_media_id BIGINT NULL COMMENT '关联录音媒体资产ID' AFTER recording_oss_id,
    ADD KEY idx_cc_call_session_recording_media (tenant_id, recording_media_id);

INSERT INTO sys_menu VALUES('9100', '声音媒体', '9000', '10', 'media-asset', 'callcenter/media-asset/index', '', 1, 0, 'C', '0', '0', 'callcenter:media-asset:list', 'headset', 103, 1, sysdate(), null, null, '声音媒体管理菜单');
INSERT INTO sys_menu VALUES('9101', '媒体查询', '9100', '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:media-asset:query', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9102', '媒体上传', '9100', '2', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:media-asset:create', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9103', '媒体修改', '9100', '3', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:media-asset:update', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9104', '媒体删除', '9100', '4', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:media-asset:delete', '#', 103, 1, sysdate(), null, null, '');
