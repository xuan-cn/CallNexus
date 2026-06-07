CREATE TABLE cc_customer_follow_up (
    id                  BIGINT          NOT NULL COMMENT 'Follow-up ID',
    tenant_id           VARCHAR(20)     NOT NULL COMMENT 'Tenant ID',
    customer_id         BIGINT          NOT NULL COMMENT 'Customer ID',
    content             VARCHAR(2000)   NOT NULL COMMENT 'Follow-up content',
    follow_up_by_name   VARCHAR(64)     NULL COMMENT 'Follow-up user name snapshot',
    create_dept         BIGINT          NULL,
    create_by           BIGINT          NULL,
    create_time         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by           BIGINT          NULL,
    update_time         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted             TINYINT         NOT NULL DEFAULT 0 COMMENT 'Logical delete',
    PRIMARY KEY (id),
    KEY idx_cc_customer_follow_up_customer (tenant_id, customer_id, create_time)
) ENGINE=InnoDB COMMENT='Customer follow-up record';
