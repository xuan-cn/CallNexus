CREATE TABLE cc_voicemail_box (
    id BIGINT NOT NULL COMMENT '语音留言箱ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    box_code VARCHAR(64) NOT NULL COMMENT '留言箱编码',
    box_name VARCHAR(128) NOT NULL COMMENT '留言箱名称',
    prompt_media_id BIGINT NOT NULL COMMENT '留言提示音媒体ID',
    max_seconds INT NOT NULL DEFAULT 120 COMMENT '最长留言秒数',
    silence_threshold INT NOT NULL DEFAULT 200 COMMENT '静音检测阈值',
    silence_hits INT NOT NULL DEFAULT 5 COMMENT '连续静音次数',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    remark VARCHAR(500) NULL COMMENT '备注',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_voicemail_box_code (tenant_id, box_code, deleted)
) ENGINE=InnoDB COMMENT='语音留言箱配置';

CREATE TABLE cc_voicemail_message (
    id BIGINT NOT NULL COMMENT '语音留言ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    voicemail_box_id BIGINT NOT NULL COMMENT '留言箱ID',
    business_call_id VARCHAR(64) NULL COMMENT '业务通话ID',
    call_session_id BIGINT NULL COMMENT '关联通话会话ID',
    node_id BIGINT NULL COMMENT 'FreeSWITCH节点ID',
    caller_number VARCHAR(64) NULL COMMENT '主叫号码',
    called_number VARCHAR(64) NULL COMMENT '被叫号码',
    customer_id BIGINT NULL COMMENT '关联客户ID',
    ticket_id BIGINT NULL COMMENT '关联工单ID',
    recording_oss_id BIGINT NOT NULL COMMENT '留言录音OSS ID',
    recording_media_id BIGINT NOT NULL COMMENT '留言录音媒体ID',
    recording_file_name VARCHAR(255) NULL COMMENT '留言录音原始文件名',
    duration_ms BIGINT NULL COMMENT '留言时长毫秒',
    status VARCHAR(16) NOT NULL DEFAULT 'UNHANDLED' COMMENT '处理状态：UNHANDLED未处理、HANDLED已处理、INVALID无效',
    handled_by BIGINT NULL COMMENT '处理人',
    handled_at DATETIME NULL COMMENT '处理时间',
    handle_remark VARCHAR(500) NULL COMMENT '处理备注',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    KEY idx_cc_voicemail_message_box (tenant_id, voicemail_box_id, create_time),
    KEY idx_cc_voicemail_message_call (tenant_id, business_call_id),
    KEY idx_cc_voicemail_message_status (tenant_id, status, create_time),
    KEY idx_cc_voicemail_message_caller (tenant_id, caller_number, create_time)
) ENGINE=InnoDB COMMENT='语音留言记录';

INSERT INTO sys_menu VALUES('9260', '语音留言', '9000', '26', 'voicemail', 'callcenter/voicemail/index', '', 1, 0, 'C', '0', '0', 'callcenter:voicemail:list', 'record', 103, 1, sysdate(), null, null, '语音留言管理菜单');
INSERT INTO sys_menu VALUES('9261', '语音留言查询', '9260', '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:voicemail:query', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9262', '留言箱新增', '9260', '2', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:voicemail-box:create', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9263', '留言箱修改', '9260', '3', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:voicemail-box:update', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9264', '留言箱删除', '9260', '4', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:voicemail-box:delete', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9265', '语音留言处理', '9260', '5', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:voicemail:handle', '#', 103, 1, sysdate(), null, null, '');
