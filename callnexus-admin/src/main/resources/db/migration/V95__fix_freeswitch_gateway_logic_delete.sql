ALTER TABLE cc_freeswitch_gateway
    MODIFY COLUMN deleted BIGINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志，0 表示未删除，删除后写入本行 ID';
