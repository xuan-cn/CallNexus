ALTER TABLE cc_ai_ticket_policy
    ADD COLUMN customer_summary_enabled TINYINT(1) NOT NULL DEFAULT 0 AFTER default_skill_group_id,
    ADD COLUMN customer_summary_template_id BIGINT NULL AFTER customer_summary_enabled,
    ADD COLUMN customer_summary_field_code VARCHAR(64) NULL AFTER customer_summary_template_id;
