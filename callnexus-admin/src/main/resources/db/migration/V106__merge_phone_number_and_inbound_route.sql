-- 将呼入入口规则归入号码管理，规则匹配与动态 Dialplan 执行逻辑保持不变。
ALTER TABLE cc_inbound_did_entry
    ADD COLUMN phone_number_id BIGINT NULL COMMENT '关联号码资源ID，仅用于后台管理归属' AFTER gateway_id,
    ADD KEY idx_cc_inbound_did_entry_phone_number (tenant_id, phone_number_id, deleted);

-- 已有 DID 规则按租户、节点、网关和号码做一次安全关联；端口、账号和 Header 规则由用户后续在号码页明确归属。
UPDATE cc_inbound_did_entry entry_rule
INNER JOIN cc_phone_number phone
    ON phone.tenant_id = entry_rule.tenant_id
    AND phone.node_id = entry_rule.node_id
    AND phone.gateway_id = entry_rule.gateway_id
    AND phone.number = entry_rule.did_number
    AND phone.deleted = 0
SET entry_rule.phone_number_id = phone.id
WHERE entry_rule.deleted = 0
  AND entry_rule.entry_type = 'DID'
  AND entry_rule.phone_number_id IS NULL;

-- 一个网关只有一个号码时，端口、账号和 Header 规则也可以无歧义地归入该号码。
UPDATE cc_inbound_did_entry entry_rule
INNER JOIN (
    SELECT tenant_id, node_id, gateway_id, MIN(id) AS phone_number_id
    FROM cc_phone_number
    WHERE deleted = 0 AND gateway_id IS NOT NULL
    GROUP BY tenant_id, node_id, gateway_id
    HAVING COUNT(*) = 1
) single_phone
    ON single_phone.tenant_id = entry_rule.tenant_id
    AND single_phone.node_id = entry_rule.node_id
    AND single_phone.gateway_id = entry_rule.gateway_id
SET entry_rule.phone_number_id = single_phone.phone_number_id
WHERE entry_rule.deleted = 0
  AND entry_rule.phone_number_id IS NULL;

-- 独立 DID 菜单退出侧栏，原路由和权限继续保留，避免历史地址失效。
UPDATE sys_menu SET visible = '1' WHERE menu_id = '9550';
UPDATE sys_menu SET menu_name = '号码与呼入路由' WHERE menu_id = '9008';
