ALTER TABLE cc_ticket_callback_policy
    ADD COLUMN allow_inbound_caller TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'Allow confirmed inbound caller for automatic callback';
