-- 扩展通话转写分句来源信息，用于通话记录按客户、坐席和 AI 展示对话。
ALTER TABLE cc_ai_call_transcript_segment
    ADD COLUMN source_type VARCHAR(32) NOT NULL DEFAULT 'RECORDING_ASR' COMMENT '分句来源：RECORDING_ASR录音转写、REALTIME_ASR实时识别、AI_GENERATED AI回复' AFTER speaker,
    ADD COLUMN leg_uuid VARCHAR(64) NULL COMMENT '关联的 FreeSWITCH 电话腿 UUID' AFTER source_type,
    ADD COLUMN agent_id BIGINT NULL COMMENT '关联坐席ID，客户或AI分句为空' AFTER leg_uuid,
    ADD COLUMN message_time DATETIME NULL COMMENT '分句发生时间' AFTER end_ms,
    ADD KEY idx_cc_ai_call_transcript_segment_source (tenant_id, business_call_id, speaker, message_time),
    ADD KEY idx_cc_ai_call_transcript_segment_leg (tenant_id, leg_uuid);
