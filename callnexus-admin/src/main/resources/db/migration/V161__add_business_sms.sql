-- Business SMS is separate from the YAML login verification configuration.
CREATE TABLE cc_sms_channel (
    id BIGINT NOT NULL PRIMARY KEY,
    channel_name VARCHAR(64) NOT NULL,
    supplier VARCHAR(16) NOT NULL,
    access_key_id VARCHAR(2048) NOT NULL,
    access_key_secret VARCHAR(2048) NOT NULL,
    sdk_app_id VARCHAR(64) NOT NULL DEFAULT '',
    signature VARCHAR(64) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    minute_limit INT NOT NULL DEFAULT 1,
    daily_limit INT NOT NULL DEFAULT 30,
    version INT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    tenant_id VARCHAR(20) NOT NULL DEFAULT '000000',
    create_dept BIGINT NULL, create_by BIGINT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by BIGINT NULL, update_time DATETIME NULL,
    KEY idx_sms_channel_tenant (tenant_id, deleted, enabled)
) ENGINE=InnoDB COMMENT='Business SMS provider channels';

CREATE TABLE cc_sms_template (
    id BIGINT NOT NULL PRIMARY KEY,
    channel_id BIGINT NOT NULL,
    template_name VARCHAR(64) NOT NULL,
    provider_template_id VARCHAR(64) NOT NULL,
    content VARCHAR(2000) NOT NULL,
    variables_json TEXT NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    version INT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    tenant_id VARCHAR(20) NOT NULL DEFAULT '000000',
    create_dept BIGINT NULL, create_by BIGINT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by BIGINT NULL, update_time DATETIME NULL,
    KEY idx_sms_template_channel (tenant_id, channel_id, deleted, enabled)
) ENGINE=InnoDB COMMENT='Business SMS approved-provider template mapping';

CREATE TABLE cc_sms_send_record (
    id BIGINT NOT NULL PRIMARY KEY,
    customer_id BIGINT NULL, channel_id BIGINT NOT NULL, template_id BIGINT NOT NULL,
    channel_name VARCHAR(64) NOT NULL, template_name VARCHAR(64) NOT NULL,
    supplier VARCHAR(16) NOT NULL, signature VARCHAR(64) NOT NULL,
    provider_template_id VARCHAR(64) NOT NULL,
    phone VARCHAR(32) NOT NULL, content VARCHAR(4000) NOT NULL, parameters_json TEXT NOT NULL,
    source VARCHAR(32) NOT NULL, operator_name VARCHAR(64) NULL,
    request_id VARCHAR(64) NOT NULL, request_hash CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL, result_code VARCHAR(96) NULL, provider_reference VARCHAR(96) NULL,
    processing_at DATETIME NULL, submitted_at DATETIME NULL,
    tenant_id VARCHAR(20) NOT NULL DEFAULT '000000',
    create_dept BIGINT NULL, create_by BIGINT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by BIGINT NULL, update_time DATETIME NULL,
    UNIQUE KEY uk_sms_send_request (tenant_id, request_id),
    KEY idx_sms_send_queue (status, create_time),
    KEY idx_sms_send_processing (status, processing_at),
    KEY idx_sms_send_phone (tenant_id, phone, create_time),
    KEY idx_sms_send_customer (tenant_id, customer_id, create_time)
) ENGINE=InnoDB COMMENT='Business SMS durable outbox and submission audit';

SET @sms_menu = (SELECT COALESCE(MAX(menu_id), 10000) + 1 FROM sys_menu);
SET @sms_customer_menu = COALESCE((SELECT menu_id FROM sys_menu WHERE component = 'callcenter/customer/index' LIMIT 1), @sms_menu);
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache,
    menu_type, visible, status, perms, icon, create_dept, create_by, create_time, remark)
VALUES (@sms_menu, '短信管理', 0, 12, 'sms-management', 'callcenter/sms/index', 1, 1,
    'C', '0', '0', 'callcenter:sms:view', 'message', 103, 1, SYSDATE(), '业务短信渠道、模板与发送记录');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, is_frame, is_cache, menu_type, visible,
    status, perms, icon, create_dept, create_by, create_time)
VALUES
(@sms_menu+1, '短信渠道查询', @sms_menu, 1, '', 1, 1, 'F', '0', '0', 'callcenter:sms-channel:list', '#', 103, 1, SYSDATE()),
(@sms_menu+2, '短信渠道配置', @sms_menu, 2, '', 1, 1, 'F', '0', '0', 'callcenter:sms-channel:manage', '#', 103, 1, SYSDATE()),
(@sms_menu+3, '短信测试发送', @sms_menu, 3, '', 1, 1, 'F', '0', '0', 'callcenter:sms-channel:test', '#', 103, 1, SYSDATE()),
(@sms_menu+4, '短信模板查询', @sms_menu, 4, '', 1, 1, 'F', '0', '0', 'callcenter:sms-template:list', '#', 103, 1, SYSDATE()),
(@sms_menu+5, '短信模板配置', @sms_menu, 5, '', 1, 1, 'F', '0', '0', 'callcenter:sms-template:manage', '#', 103, 1, SYSDATE()),
(@sms_menu+6, '短信发送记录', @sms_menu, 6, '', 1, 1, 'F', '0', '0', 'callcenter:sms-record:list', '#', 103, 1, SYSDATE()),
(@sms_menu+7, '客户发送短信', @sms_customer_menu, 20, '', 1, 1, 'F', '0', '0', 'callcenter:customer:sms', '#', 103, 1, SYSDATE());

INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
SELECT role_id, menu_id FROM sys_role CROSS JOIN sys_menu
WHERE role_key IN ('superadmin', 'admin') AND menu_id BETWEEN @sms_menu AND @sms_menu+7;
