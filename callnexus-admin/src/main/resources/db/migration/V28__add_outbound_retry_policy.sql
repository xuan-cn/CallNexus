ALTER TABLE cc_outbound_task
    ADD COLUMN auto_retry_enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用系统自动重呼' AFTER description,
    ADD COLUMN max_retry_count INT NOT NULL DEFAULT 2 COMMENT '最大自动重呼次数，不包含首次拨打' AFTER auto_retry_enabled,
    ADD COLUMN retry_interval_minutes INT NOT NULL DEFAULT 30 COMMENT '自动重呼间隔，单位分钟' AFTER max_retry_count,
    ADD COLUMN retry_result_codes VARCHAR(128) NOT NULL DEFAULT 'NO_ANSWER,BUSY,OTHER'
        COMMENT '触发自动重呼的系统建议结果，英文逗号分隔' AFTER retry_interval_minutes;

ALTER TABLE cc_outbound_member
    ADD COLUMN completion_reason VARCHAR(32) NULL
        COMMENT '名单结束原因：MANUAL人工确认、SYSTEM系统自动完成、RETRY_LIMIT_REACHED达到重呼上限'
        AFTER completed_at;
