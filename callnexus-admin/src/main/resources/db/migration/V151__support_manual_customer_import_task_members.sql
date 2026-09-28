-- Allow customers created manually to belong to a customer import task without
-- fabricating an Excel upload batch.
ALTER TABLE cc_customer_import_row
    MODIFY COLUMN batch_id BIGINT NULL COMMENT '上传批次ID，手工录入时为空',
    ADD COLUMN source_type VARCHAR(16) NOT NULL DEFAULT 'FILE' COMMENT '来源：FILE、MANUAL' AFTER batch_id,
    ADD COLUMN active_manual_customer_id BIGINT
        GENERATED ALWAYS AS (
            CASE WHEN deleted = 0 AND source_type = 'MANUAL' THEN customer_id ELSE NULL END
        ) STORED COMMENT '有效手工关联客户ID',
    ADD UNIQUE KEY uk_cc_customer_import_manual_customer (tenant_id, task_id, active_manual_customer_id),
    ADD KEY idx_cc_customer_import_row_source (tenant_id, task_id, source_type, status);
