CREATE TABLE IF NOT EXISTS cc_customer_phone (
    id               BIGINT       NOT NULL COMMENT '客户电话号码ID',
    tenant_id        VARCHAR(20)  NOT NULL COMMENT '租户ID',
    customer_id      BIGINT       NOT NULL COMMENT '客户ID',
    phone_number     VARCHAR(32)  NOT NULL COMMENT '电话号码',
    normalized_phone VARCHAR(32)  NOT NULL COMMENT '规范化电话号码',
    phone_type       VARCHAR(16)  NOT NULL DEFAULT 'OTHER' COMMENT '号码类型：MOBILE、HOME、WORK、OTHER',
    phone_label      VARCHAR(32)  NULL COMMENT '号码标签',
    primary_flag     TINYINT      NOT NULL DEFAULT 0 COMMENT '是否主号码',
    enabled          TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用',
    sort_order       INT          NOT NULL DEFAULT 0 COMMENT '排序',
    create_dept      BIGINT       NULL COMMENT '创建部门',
    create_by        BIGINT       NULL COMMENT '创建人',
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by        BIGINT       NULL COMMENT '更新人',
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version          INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_customer_phone_number (tenant_id, normalized_phone),
    KEY idx_cc_customer_phone_customer (tenant_id, customer_id, primary_flag, sort_order)
) ENGINE=InnoDB COMMENT='客户电话号码';

INSERT IGNORE INTO cc_customer_phone (
    id, tenant_id, customer_id, phone_number, normalized_phone, phone_type,
    phone_label, primary_flag, enabled, sort_order,
    create_dept, create_by, create_time, update_by, update_time, version
)
SELECT
    id, tenant_id, id, primary_phone, primary_phone,
    CASE WHEN primary_phone REGEXP '^1[3-9][0-9]{9}$' THEN 'MOBILE' ELSE 'OTHER' END,
    '主号码', 1, 1, 0,
    create_dept, create_by, create_time, update_by, update_time, 0
FROM cc_customer customer
WHERE customer.deleted = 0
  AND customer.primary_phone IS NOT NULL
  AND customer.primary_phone <> ''
  AND NOT EXISTS (
      SELECT 1
      FROM cc_customer_phone phone
      WHERE phone.tenant_id = customer.tenant_id
        AND phone.normalized_phone = customer.primary_phone
  );
