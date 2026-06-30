CREATE TABLE cc_dispatch_operator_extension (
    id BIGINT NOT NULL COMMENT '调度员分机绑定ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    user_id BIGINT NOT NULL COMMENT '系统用户ID',
    sip_account_id BIGINT NOT NULL COMMENT '调度操作使用的SIP账号ID',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_dispatch_operator_user (tenant_id, user_id),
    UNIQUE KEY uk_dispatch_operator_sip (tenant_id, sip_account_id)
) ENGINE=InnoDB COMMENT='调度员操作分机绑定表';

INSERT IGNORE INTO sys_menu VALUES('9308', '绑定调度分机', '9300', '8', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:dispatch-control:operator-extension', '#', 103, 1, SYSDATE(), NULL, NULL, '允许调度员绑定独立SIP实体分机');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9308'
FROM sys_role_menu
WHERE menu_id IN ('9300', '9301');
