CREATE TABLE cc_skill_group (
    id BIGINT NOT NULL COMMENT '技能组ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    group_code VARCHAR(32) NOT NULL COMMENT '技能组编码',
    group_name VARCHAR(64) NOT NULL COMMENT '技能组名称',
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
    UNIQUE KEY uk_cc_skill_group_code (tenant_id, group_code, deleted)
) ENGINE=InnoDB COMMENT='坐席技能组';

CREATE TABLE cc_skill_group_member (
    id BIGINT NOT NULL COMMENT '技能组成员ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    skill_group_id BIGINT NOT NULL COMMENT '技能组ID',
    agent_id BIGINT NOT NULL COMMENT '坐席ID',
    skill_level INT NOT NULL DEFAULT 1 COMMENT '技能等级，数值越大技能越高',
    priority INT NOT NULL DEFAULT 1 COMMENT '分配优先级，数值越小优先级越高',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_skill_group_member (tenant_id, skill_group_id, agent_id, deleted),
    KEY idx_cc_skill_group_member_agent (tenant_id, agent_id)
) ENGINE=InnoDB COMMENT='技能组坐席成员';

CREATE TABLE cc_call_queue (
    id BIGINT NOT NULL COMMENT '呼叫队列ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    queue_code VARCHAR(32) NOT NULL COMMENT '队列编码，同时作为FreeSWITCH callcenter队列名称',
    queue_name VARCHAR(64) NOT NULL COMMENT '队列名称',
    node_group_id BIGINT NOT NULL COMMENT '目标FreeSWITCH节点组ID',
    skill_group_id BIGINT NOT NULL COMMENT '接听技能组ID',
    strategy VARCHAR(32) NOT NULL DEFAULT 'LONGEST_IDLE_AGENT' COMMENT '分配策略',
    wait_media_id BIGINT NULL COMMENT '队列等待音媒体ID',
    max_wait_seconds INT NOT NULL DEFAULT 300 COMMENT '最大等待时长，单位秒',
    ring_timeout_seconds INT NOT NULL DEFAULT 20 COMMENT '单次振铃超时时长，单位秒',
    max_no_answer INT NOT NULL DEFAULT 3 COMMENT '坐席最大未接次数',
    wrap_up_seconds INT NOT NULL DEFAULT 10 COMMENT '话后整理时长，单位秒',
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
    UNIQUE KEY uk_cc_call_queue_code (tenant_id, queue_code, deleted),
    KEY idx_cc_call_queue_skill_group (tenant_id, skill_group_id, enabled),
    KEY idx_cc_call_queue_node_group (tenant_id, node_group_id, enabled)
) ENGINE=InnoDB COMMENT='呼叫中心队列';

INSERT INTO sys_menu VALUES('9130', '技能组管理', '9000', '13', 'skill-group', 'callcenter/skill-group/index', '', 1, 0, 'C', '0', '0', 'callcenter:skill-group:list', 'user', 103, 1, sysdate(), null, null, '坐席技能组管理菜单');
INSERT INTO sys_menu VALUES('9131', '技能组查询', '9130', '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:skill-group:query', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9132', '技能组新增', '9130', '2', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:skill-group:create', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9133', '技能组修改', '9130', '3', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:skill-group:update', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9134', '技能组删除', '9130', '4', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:skill-group:delete', '#', 103, 1, sysdate(), null, null, '');

INSERT INTO sys_menu VALUES('9140', '队列管理', '9000', '14', 'call-queue', 'callcenter/call-queue/index', '', 1, 0, 'C', '0', '0', 'callcenter:call-queue:list', 'list', 103, 1, sysdate(), null, null, '呼叫队列管理菜单');
INSERT INTO sys_menu VALUES('9141', '队列查询', '9140', '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:call-queue:query', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9142', '队列新增', '9140', '2', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:call-queue:create', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9143', '队列修改', '9140', '3', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:call-queue:update', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9144', '队列删除', '9140', '4', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:call-queue:delete', '#', 103, 1, sysdate(), null, null, '');
