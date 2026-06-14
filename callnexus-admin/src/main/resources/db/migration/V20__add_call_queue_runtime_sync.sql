ALTER TABLE cc_call_queue
    ADD COLUMN sync_status VARCHAR(16) NOT NULL DEFAULT 'NOT_SYNCED' COMMENT 'FreeSWITCH同步状态：未同步、已同步、部分成功、失败' AFTER wrap_up_seconds,
    ADD COLUMN last_synced_at DATETIME NULL COMMENT '最近同步时间' AFTER sync_status,
    ADD COLUMN sync_error VARCHAR(1000) NULL COMMENT '最近同步失败原因' AFTER last_synced_at;
