ALTER TABLE cc_freeswitch_gateway
    ADD COLUMN ping INT NOT NULL DEFAULT 0 COMMENT 'Gateway OPTIONS ping interval seconds, 0 means disabled'
    AFTER caller_id_number;
