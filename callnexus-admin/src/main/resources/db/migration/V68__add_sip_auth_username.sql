-- SIP 分机增加独立鉴权名。
-- 分机号用于业务拨号和展示，鉴权名用于 SIP REGISTER 认证，降低分机号暴露后的注册风险。
ALTER TABLE cc_sip_account
    ADD COLUMN auth_username VARCHAR(64) NULL COMMENT 'SIP鉴权名，用于REGISTER认证' AFTER extension;

UPDATE cc_sip_account
SET auth_username = CONCAT('cnx_', LOWER(SUBSTRING(REPLACE(UUID(), '-', ''), 1, 16)))
WHERE auth_username IS NULL OR auth_username = '';

ALTER TABLE cc_sip_account
    MODIFY COLUMN auth_username VARCHAR(64) NOT NULL COMMENT 'SIP鉴权名，用于REGISTER认证';

ALTER TABLE cc_sip_account
    ADD UNIQUE KEY uk_cc_sip_account_auth_username (tenant_id, auth_username, deleted);
