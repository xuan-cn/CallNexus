ALTER TABLE cc_outbound_member
    DROP INDEX uk_cc_outbound_member_active_customer,
    MODIFY COLUMN customer_id BIGINT NULL COMMENT '客户ID；工单回访可无客户档案',
    MODIFY COLUMN active_customer_id BIGINT
        GENERATED ALWAYS AS (CASE WHEN deleted = 0 AND source_type <> 'TICKET_CALLBACK' THEN customer_id ELSE NULL END) STORED,
    ADD UNIQUE KEY uk_cc_outbound_member_active_customer (tenant_id, task_id, active_customer_id),
    ADD KEY idx_cc_outbound_member_source (tenant_id, source_type, source_id, deleted);

    ALTER TABLE cc_outbound_attempt
        MODIFY COLUMN customer_id BIGINT NULL COMMENT '客户ID；工单回访可无客户档案';
