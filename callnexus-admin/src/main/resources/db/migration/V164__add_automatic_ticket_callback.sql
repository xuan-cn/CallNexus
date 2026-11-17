    CREATE TABLE cc_ticket_callback_policy (
        tenant_id VARCHAR(20) NOT NULL,
        template_id BIGINT NOT NULL,
        mode VARCHAR(12) NOT NULL,
        task_id BIGINT NULL,
        delay_minutes INT NOT NULL,
        expiry_minutes INT NOT NULL,
        revision VARCHAR(36) NOT NULL,
        enabled_since DATETIME(3) NOT NULL,
        PRIMARY KEY (tenant_id, template_id),
        KEY idx_callback_policy_task (tenant_id, task_id, mode)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

    CREATE TABLE cc_ticket_callback_contact (
        tenant_id VARCHAR(20) NOT NULL,
        ticket_id BIGINT NOT NULL,
        phone VARCHAR(32) NOT NULL,
        revision VARCHAR(36) NOT NULL,
        PRIMARY KEY (tenant_id, ticket_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

    ALTER TABLE cc_ticket
        ADD KEY idx_ticket_callback_scan (tenant_id, template_id, ticket_status, resolved_at);

    ALTER TABLE cc_outbound_member
        ADD COLUMN callback_resolved_at DATETIME NULL COMMENT 'Ticket resolution cycle',
        ADD COLUMN callback_policy_revision VARCHAR(36) NULL COMMENT 'Automatic callback policy snapshot',
        ADD COLUMN callback_contact_revision VARCHAR(36) NULL COMMENT 'Automatic callback contact snapshot',
        ADD KEY idx_callback_cycle (tenant_id, source_type, source_id, callback_resolved_at, deleted);
