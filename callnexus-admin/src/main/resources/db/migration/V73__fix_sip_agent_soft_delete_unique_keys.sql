-- 修复 SIP 账号、坐席和分机绑定重复软删除后无法重建的问题。
-- MySQL 唯一索引允许多个 NULL，因此只为未删除记录生成活动业务键。

ALTER TABLE cc_sip_account
    DROP INDEX uk_cc_sip_account_tenant_extension,
    DROP INDEX uk_cc_sip_account_auth_username,
    ADD COLUMN active_extension VARCHAR(32)
        GENERATED ALWAYS AS (CASE WHEN deleted = 0 THEN extension ELSE NULL END) STORED
        COMMENT '未删除记录的分机号唯一键',
    ADD COLUMN active_auth_username VARCHAR(64)
        GENERATED ALWAYS AS (CASE WHEN deleted = 0 THEN auth_username ELSE NULL END) STORED
        COMMENT '未删除记录的鉴权名唯一键',
    ADD UNIQUE KEY uk_cc_sip_account_active_extension (tenant_id, active_extension),
    ADD UNIQUE KEY uk_cc_sip_account_active_auth_username (tenant_id, active_auth_username);

ALTER TABLE cc_agent
    DROP INDEX uk_cc_agent_tenant_code,
    DROP INDEX uk_cc_agent_tenant_user,
    ADD COLUMN active_agent_code VARCHAR(32)
        GENERATED ALWAYS AS (CASE WHEN deleted = 0 THEN agent_code ELSE NULL END) STORED
        COMMENT '未删除记录的坐席编码唯一键',
    ADD COLUMN active_user_id BIGINT
        GENERATED ALWAYS AS (CASE WHEN deleted = 0 THEN user_id ELSE NULL END) STORED
        COMMENT '未删除记录的系统用户唯一键',
    ADD UNIQUE KEY uk_cc_agent_active_code (tenant_id, active_agent_code),
    ADD UNIQUE KEY uk_cc_agent_active_user (tenant_id, active_user_id);

ALTER TABLE cc_agent_extension
    DROP INDEX uk_cc_agent_extension_agent,
    DROP INDEX uk_cc_agent_extension_sip,
    ADD COLUMN active_agent_id BIGINT
        GENERATED ALWAYS AS (CASE WHEN deleted = 0 THEN agent_id ELSE NULL END) STORED
        COMMENT '未删除记录的坐席绑定唯一键',
    ADD COLUMN active_sip_account_id BIGINT
        GENERATED ALWAYS AS (CASE WHEN deleted = 0 THEN sip_account_id ELSE NULL END) STORED
        COMMENT '未删除记录的 SIP 账号绑定唯一键',
    ADD UNIQUE KEY uk_cc_agent_extension_active_agent (tenant_id, active_agent_id),
    ADD UNIQUE KEY uk_cc_agent_extension_active_sip (tenant_id, active_sip_account_id);
