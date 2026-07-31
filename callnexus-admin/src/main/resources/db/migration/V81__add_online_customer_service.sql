-- 在线客服渠道、访客、会话和消息。
CREATE TABLE IF NOT EXISTS cc_chat_channel (
    id BIGINT NOT NULL COMMENT '渠道ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    channel_key VARCHAR(64) NOT NULL COMMENT '公开渠道键',
    channel_name VARCHAR(128) NOT NULL COMMENT '渠道名称',
    skill_group_id BIGINT NULL COMMENT '接待技能组ID',
    welcome_message VARCHAR(1000) NULL COMMENT '欢迎语',
    offline_message VARCHAR(1000) NULL COMMENT '离线提示',
    allowed_origins TEXT NULL COMMENT '允许嵌入的来源，逗号或换行分隔',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_chat_channel_key (channel_key, deleted),
    KEY idx_cc_chat_channel_tenant (tenant_id, enabled)
) ENGINE=InnoDB COMMENT='在线客服渠道';

CREATE TABLE IF NOT EXISTS cc_chat_visitor (
    id BIGINT NOT NULL COMMENT '访客ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    external_id VARCHAR(128) NULL COMMENT '接入方访客标识',
    visitor_name VARCHAR(128) NULL COMMENT '访客名称',
    phone VARCHAR(64) NULL COMMENT '联系电话',
    email VARCHAR(255) NULL COMMENT '邮箱',
    last_seen_at DATETIME NULL COMMENT '最后活跃时间',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    KEY idx_cc_chat_visitor_external (tenant_id, external_id),
    KEY idx_cc_chat_visitor_phone (tenant_id, phone)
) ENGINE=InnoDB COMMENT='在线客服访客';

CREATE TABLE IF NOT EXISTS cc_chat_conversation (
    id BIGINT NOT NULL COMMENT '会话ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    conversation_no VARCHAR(40) NOT NULL COMMENT '会话编号',
    channel_id BIGINT NOT NULL COMMENT '渠道ID',
    visitor_id BIGINT NOT NULL COMMENT '访客ID',
    access_token_hash CHAR(64) NOT NULL COMMENT '访客访问令牌哈希',
    status VARCHAR(32) NOT NULL COMMENT '状态：QUEUING、ACTIVE、CLOSED、ABANDONED',
    priority INT NOT NULL DEFAULT 0 COMMENT '优先级',
    assigned_user_id BIGINT NULL COMMENT '当前接待用户ID',
    assigned_user_name VARCHAR(128) NULL COMMENT '当前接待用户名称',
    customer_id BIGINT NULL COMMENT '关联客户ID',
    ticket_id BIGINT NULL COMMENT '关联工单ID',
    queued_at DATETIME NOT NULL COMMENT '进入队列时间',
    assigned_at DATETIME NULL COMMENT '分配时间',
    closed_at DATETIME NULL COMMENT '关闭时间',
    last_message_at DATETIME NULL COMMENT '最后消息时间',
    unread_agent_count INT NOT NULL DEFAULT 0 COMMENT '坐席未读数',
    unread_visitor_count INT NOT NULL DEFAULT 0 COMMENT '访客未读数',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_chat_conversation_no (tenant_id, conversation_no),
    KEY idx_cc_chat_conversation_queue (tenant_id, status, channel_id, priority, queued_at),
    KEY idx_cc_chat_conversation_agent (tenant_id, assigned_user_id, status, last_message_at)
) ENGINE=InnoDB COMMENT='在线客服会话';

CREATE TABLE IF NOT EXISTS cc_chat_message (
    id BIGINT NOT NULL COMMENT '消息ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    conversation_id BIGINT NOT NULL COMMENT '会话ID',
    sender_type VARCHAR(16) NOT NULL COMMENT '发送方：VISITOR、AGENT、SYSTEM',
    sender_id BIGINT NULL COMMENT '发送方ID',
    sender_name VARCHAR(128) NULL COMMENT '发送方名称',
    message_type VARCHAR(16) NOT NULL DEFAULT 'TEXT' COMMENT '消息类型',
    content TEXT NOT NULL COMMENT '消息内容',
    client_message_id VARCHAR(64) NULL COMMENT '客户端幂等消息ID',
    sent_at DATETIME NOT NULL COMMENT '发送时间',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_chat_message_client (tenant_id, conversation_id, sender_type, client_message_id),
    KEY idx_cc_chat_message_conversation (tenant_id, conversation_id, id)
) ENGINE=InnoDB COMMENT='在线客服消息';

INSERT IGNORE INTO sys_menu VALUES(
    '9510', '在线客服', '9000', '7', 'callcenter-chat', NULL, '', 1, 0, 'M', '0', '0',
    '', 'chat-dot-round', 103, 1, SYSDATE(), NULL, NULL, '网站在线客服、访客会话和服务工作台'
);
INSERT IGNORE INTO sys_menu VALUES(
    '9500', '在线客服渠道', '9510', '1', 'chat-channel', 'callcenter/chat-channel/index', '', 1, 0, 'C', '0', '0',
    'callcenter:chat-channel:list', 'connection', 103, 1, SYSDATE(), NULL, NULL, '网站在线客服接入渠道'
);
INSERT IGNORE INTO sys_menu VALUES(
    '9501', '在线客服工作台', '9510', '2', 'chat-workbench', 'callcenter/chat-workbench/index', '', 1, 0, 'C', '0', '0',
    'callcenter:chat-conversation:list', 'chat-dot-round', 103, 1, SYSDATE(), NULL, NULL, '访客排队、领取和在线沟通'
);
INSERT IGNORE INTO sys_menu VALUES('9502', '渠道查询', '9500', '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:chat-channel:query', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9503', '渠道新增', '9500', '2', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:chat-channel:create', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9504', '渠道修改', '9500', '3', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:chat-channel:update', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9505', '渠道删除', '9500', '4', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:chat-channel:delete', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9506', '会话领取', '9501', '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:chat-conversation:claim', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9507', '会话回复', '9501', '2', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:chat-conversation:reply', '#', 103, 1, SYSDATE(), NULL, NULL, '');
INSERT IGNORE INTO sys_menu VALUES('9508', '会话关闭', '9501', '3', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:chat-conversation:close', '#', 103, 1, SYSDATE(), NULL, NULL, '');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9500' FROM sys_role_menu WHERE menu_id = '9004';
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9501' FROM sys_role_menu WHERE menu_id = '9002';
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9502' FROM sys_role_menu WHERE menu_id = '9004';
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9503' FROM sys_role_menu WHERE menu_id = '9004';
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9504' FROM sys_role_menu WHERE menu_id = '9004';
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9505' FROM sys_role_menu WHERE menu_id = '9004';
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9506' FROM sys_role_menu WHERE menu_id = '9002';
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9507' FROM sys_role_menu WHERE menu_id = '9002';
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9508' FROM sys_role_menu WHERE menu_id = '9002';
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9510' FROM sys_role_menu WHERE menu_id IN ('9500', '9501');
