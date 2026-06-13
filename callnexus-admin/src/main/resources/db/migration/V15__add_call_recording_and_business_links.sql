ALTER TABLE cc_call_session
    ADD COLUMN customer_id BIGINT NULL COMMENT 'Explicit related customer ID' AFTER agent_extension,
    ADD COLUMN ticket_id BIGINT NULL COMMENT 'Explicit related ticket ID' AFTER customer_id,
    ADD COLUMN recording_oss_id BIGINT NULL COMMENT 'Recording OSS object ID' AFTER hangup_cause,
    ADD COLUMN recording_file_name VARCHAR(255) NULL COMMENT 'Original recording file name' AFTER recording_oss_id,
    ADD COLUMN recording_status VARCHAR(16) NOT NULL DEFAULT 'NONE' COMMENT 'NONE/PENDING/UPLOADED/FAILED' AFTER recording_file_name,
    ADD KEY idx_cc_call_session_customer (tenant_id, customer_id, started_at),
    ADD KEY idx_cc_call_session_ticket (tenant_id, ticket_id, started_at);
