ALTER TABLE cc_form_template
    ADD COLUMN deadline_enabled TINYINT NOT NULL DEFAULT 0 COMMENT 'Whether ticket resolution deadline is enabled' AFTER workflow_code,
    ADD COLUMN resolution_limit_minutes INT NULL COMMENT 'Ticket resolution limit in natural minutes' AFTER deadline_enabled,
    ADD COLUMN remind_before_minutes INT NULL COMMENT 'Minutes before deadline to send reminder' AFTER resolution_limit_minutes;

ALTER TABLE cc_ticket
    ADD COLUMN resolution_limit_minutes INT NULL COMMENT 'Resolution limit snapshot in natural minutes' AFTER closed_at,
    ADD COLUMN due_at DATETIME NULL COMMENT 'Expected resolution deadline' AFTER resolution_limit_minutes,
    ADD COLUMN remind_at DATETIME NULL COMMENT 'Upcoming deadline reminder time' AFTER due_at,
    ADD COLUMN deadline_status VARCHAR(24) NULL COMMENT 'NORMAL, DUE_SOON, OVERDUE, COMPLETED, COMPLETED_OVERDUE or CANCELLED' AFTER remind_at,
    ADD COLUMN due_soon_reminded_at DATETIME NULL COMMENT 'Upcoming deadline reminder sent time' AFTER deadline_status,
    ADD COLUMN overdue_at DATETIME NULL COMMENT 'First detected overdue time' AFTER due_soon_reminded_at,
    ADD COLUMN overdue_reminded_at DATETIME NULL COMMENT 'Overdue reminder sent time' AFTER overdue_at,
    ADD KEY idx_cc_ticket_deadline (tenant_id, ticket_status, due_at);
