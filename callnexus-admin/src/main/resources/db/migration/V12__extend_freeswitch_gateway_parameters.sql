ALTER TABLE cc_freeswitch_gateway
    ADD COLUMN expire_seconds INT NOT NULL DEFAULT 60 COMMENT 'Gateway REGISTER expiration interval seconds'
    AFTER ping,
    ADD COLUMN retry_seconds INT NOT NULL DEFAULT 30 COMMENT 'Gateway registration retry interval seconds'
    AFTER expire_seconds,
    ADD COLUMN ping_max INT NOT NULL DEFAULT 3 COMMENT 'Consecutive ping failures before marking gateway down'
    AFTER retry_seconds,
    ADD COLUMN ping_min INT NOT NULL DEFAULT 1 COMMENT 'Consecutive ping successes before marking gateway up'
    AFTER ping_max,
    ADD COLUMN caller_id_in_from TINYINT NOT NULL DEFAULT 1 COMMENT 'Whether caller ID is sent in From header'
    AFTER ping_min,
    ADD COLUMN from_user VARCHAR(64) NULL COMMENT 'SIP From user, defaults to username'
    AFTER caller_id_in_from,
    ADD COLUMN from_domain VARCHAR(128) NULL COMMENT 'SIP From domain, defaults to realm'
    AFTER from_user,
    ADD COLUMN contact_params VARCHAR(255) NULL COMMENT 'Additional SIP Contact parameters'
    AFTER from_domain,
    ADD COLUMN dialplan_context VARCHAR(64) NOT NULL DEFAULT 'public' COMMENT 'Inbound dialplan context'
    AFTER contact_params,
    ADD COLUMN extension VARCHAR(64) NOT NULL DEFAULT 'auto_to_user' COMMENT 'Inbound gateway extension'
    AFTER dialplan_context,
    ADD COLUMN description VARCHAR(255) NULL COMMENT 'Gateway description'
    AFTER extension;
