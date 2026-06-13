ALTER TABLE cc_freeswitch_node
    ADD COLUMN agent_enabled TINYINT NOT NULL DEFAULT 0 COMMENT '是否启用媒体同步Agent' AFTER enabled,
    ADD COLUMN agent_token_hash VARCHAR(64) NULL COMMENT '媒体同步Agent访问令牌SHA-256摘要' AFTER agent_enabled,
    ADD COLUMN agent_last_heartbeat DATETIME NULL COMMENT '媒体同步Agent最后心跳时间' AFTER agent_token_hash,
    ADD COLUMN agent_version VARCHAR(32) NULL COMMENT '媒体同步Agent版本' AFTER agent_last_heartbeat,
    ADD COLUMN media_root_path VARCHAR(255) NOT NULL DEFAULT '/var/lib/freeswitch/sounds/callnexus' COMMENT '媒体文件根目录' AFTER agent_version;

ALTER TABLE cc_media_asset
    ADD COLUMN latest_version_id BIGINT NULL COMMENT '最新媒体版本ID' AFTER reference_count,
    ADD COLUMN current_publication_id BIGINT NULL COMMENT '当前发布记录ID' AFTER latest_version_id,
    ADD COLUMN publish_status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT '发布状态：草稿、发布中、部分发布、已发布、失败、已取消发布' AFTER current_publication_id;

CREATE TABLE cc_freeswitch_node_group (
    id BIGINT NOT NULL COMMENT '节点组ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    group_code VARCHAR(32) NOT NULL COMMENT '节点组编码',
    group_name VARCHAR(64) NOT NULL COMMENT '节点组名称',
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
    UNIQUE KEY uk_cc_fs_node_group_code (tenant_id, group_code, deleted)
) ENGINE=InnoDB COMMENT='FreeSWITCH通用节点组';

CREATE TABLE cc_freeswitch_node_group_member (
    id BIGINT NOT NULL COMMENT '节点组成员ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    group_id BIGINT NOT NULL COMMENT '节点组ID',
    node_id BIGINT NOT NULL COMMENT '节点ID（FreeSWITCH）',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_fs_node_group_member (tenant_id, group_id, node_id, deleted),
    KEY idx_cc_fs_node_group_member_node (tenant_id, node_id)
) ENGINE=InnoDB COMMENT='FreeSWITCH节点组成员';

CREATE TABLE cc_media_asset_version (
    id BIGINT NOT NULL COMMENT '媒体版本ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    media_id BIGINT NOT NULL COMMENT '媒体资产ID',
    version_no INT NOT NULL COMMENT '版本号',
    oss_id BIGINT NOT NULL COMMENT '源文件OSS ID',
    original_file_name VARCHAR(255) NULL COMMENT '原始文件名',
    content_type VARCHAR(128) NULL COMMENT '文件MIME类型',
    file_suffix VARCHAR(32) NULL COMMENT '文件扩展名',
    file_size BIGINT NULL COMMENT '文件大小（字节）',
    duration_ms BIGINT NULL COMMENT '音频时长（毫秒）',
    sample_rate INT NULL COMMENT '采样率',
    channels INT NULL COMMENT '声道数',
    codec VARCHAR(32) NULL COMMENT '音频编码',
    checksum VARCHAR(128) NULL COMMENT '文件校验值',
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT '版本状态：草稿、已发布、已取消发布',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_media_asset_version (tenant_id, media_id, version_no, deleted)
) ENGINE=InnoDB COMMENT='声音媒体不可变版本';

CREATE TABLE cc_media_publication (
    id BIGINT NOT NULL COMMENT '媒体发布记录ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    media_id BIGINT NOT NULL COMMENT '媒体资产ID',
    version_id BIGINT NOT NULL COMMENT '媒体版本ID',
    node_group_id BIGINT NOT NULL COMMENT '目标节点组ID',
    status VARCHAR(16) NOT NULL DEFAULT 'PUBLISHING' COMMENT '发布状态：发布中、部分发布、已发布、失败、已取消发布',
    success_count INT NOT NULL DEFAULT 0 COMMENT '同步成功节点数',
    failed_count INT NOT NULL DEFAULT 0 COMMENT '同步失败节点数',
    target_count INT NOT NULL DEFAULT 0 COMMENT '目标节点数',
    published_at DATETIME NULL COMMENT '发布时间',
    unpublished_at DATETIME NULL COMMENT '取消发布时间',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    KEY idx_cc_media_publication_media (tenant_id, media_id, status),
    KEY idx_cc_media_publication_group (tenant_id, node_group_id, status)
) ENGINE=InnoDB COMMENT='声音媒体发布记录';

CREATE TABLE cc_media_node_sync (
    id BIGINT NOT NULL COMMENT '节点同步任务ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    publication_id BIGINT NOT NULL COMMENT '媒体发布记录ID',
    media_id BIGINT NOT NULL COMMENT '媒体资产ID',
    version_id BIGINT NOT NULL COMMENT '媒体版本ID',
    node_id BIGINT NOT NULL COMMENT '节点ID（FreeSWITCH）',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '同步状态：待处理、处理中、成功、失败',
    target_path VARCHAR(500) NOT NULL COMMENT '节点目标文件路径',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '自动重试次数',
    next_retry_at DATETIME NULL COMMENT '下次自动重试时间',
    lease_token VARCHAR(64) NULL COMMENT '任务租约令牌',
    lease_expires_at DATETIME NULL COMMENT '任务租约过期时间',
    failure_reason VARCHAR(1000) NULL COMMENT '同步失败原因',
    synced_at DATETIME NULL COMMENT '同步完成时间',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_media_node_sync (tenant_id, publication_id, node_id, deleted),
    KEY idx_cc_media_node_sync_claim (node_id, status, next_retry_at, lease_expires_at)
) ENGINE=InnoDB COMMENT='声音媒体节点同步任务';

INSERT INTO cc_media_asset_version (
    id, tenant_id, media_id, version_no, oss_id, original_file_name, content_type, file_suffix,
    file_size, duration_ms, sample_rate, channels, codec, status, create_dept, create_by, create_time,
    update_by, update_time, version, deleted
)
SELECT id, tenant_id, id, 1, oss_id, original_file_name, content_type, file_suffix,
       file_size, duration_ms, sample_rate, channels, codec, 'DRAFT', create_dept, create_by, create_time,
       update_by, update_time, 0, 0
FROM cc_media_asset
WHERE category <> 'CALL_RECORDING' AND deleted = 0;

UPDATE cc_media_asset SET latest_version_id = id WHERE category <> 'CALL_RECORDING' AND deleted = 0;

INSERT INTO sys_menu VALUES('9110', '节点组管理', '9000', '11', 'freeswitch-node-group', 'callcenter/freeswitch-node-group/index', '', 1, 0, 'C', '0', '0', 'callcenter:freeswitch-node-group:list', 'cluster', 103, 1, sysdate(), null, null, 'FreeSWITCH节点组管理菜单');
INSERT INTO sys_menu VALUES('9111', '节点组查询', '9110', '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:freeswitch-node-group:query', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9112', '节点组新增', '9110', '2', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:freeswitch-node-group:create', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9113', '节点组修改', '9110', '3', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:freeswitch-node-group:update', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9114', '节点组删除', '9110', '4', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:freeswitch-node-group:delete', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9115', '媒体发布', '9100', '5', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:media-asset:publish', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9116', '媒体同步', '9100', '6', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:media-asset:sync', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9117', '节点Agent令牌', '9003', '5', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:freeswitch-node:agent-token', '#', 103, 1, sysdate(), null, null, '');
