ALTER TABLE cc_outbound_member
    ADD COLUMN lease_expires_at DATETIME NULL COMMENT '名单领取租约到期时间' AFTER claimed_at,
    ADD KEY idx_cc_outbound_member_lease (tenant_id, status, lease_expires_at);

