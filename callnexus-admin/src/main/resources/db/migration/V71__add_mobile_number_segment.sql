-- 手机号段归属地表，用于手机号省市和运营商识别，后续可用于按地区或运营商选线。
CREATE TABLE IF NOT EXISTS cc_mobile_number_segment (
    id BIGINT NOT NULL COMMENT '手机号段ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    country_code VARCHAR(8) NOT NULL DEFAULT '86' COMMENT '国家码',
    segment_prefix VARCHAR(16) NOT NULL COMMENT '手机号段前缀，建议使用7位号段',
    province VARCHAR(64) NOT NULL COMMENT '省份',
    city VARCHAR(64) NOT NULL COMMENT '城市',
    carrier VARCHAR(32) NOT NULL COMMENT '运营商：移动、联通、电信、广电等',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '启用状态',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_mobile_segment (tenant_id, country_code, segment_prefix, deleted),
    KEY idx_cc_mobile_segment_region (tenant_id, province, city, carrier, enabled)
) ENGINE=InnoDB COMMENT='手机号段归属地表';

INSERT IGNORE INTO sys_menu VALUES('9480', '手机号段', '9000', '71', 'mobile-segment', 'callcenter/mobile-segment/index', '', 1, 0, 'C', '0', '0', 'callcenter:mobile-segment:list', 'phone', 103, 1, sysdate(), null, null, '维护手机号段归属地和运营商');
INSERT IGNORE INTO sys_menu VALUES('9481', '号段查询', '9480', '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:mobile-segment:query', '#', 103, 1, sysdate(), null, null, '');
INSERT IGNORE INTO sys_menu VALUES('9482', '号段新增', '9480', '2', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:mobile-segment:create', '#', 103, 1, sysdate(), null, null, '');
INSERT IGNORE INTO sys_menu VALUES('9483', '号段修改', '9480', '3', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:mobile-segment:update', '#', 103, 1, sysdate(), null, null, '');
INSERT IGNORE INTO sys_menu VALUES('9484', '号段删除', '9480', '4', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:mobile-segment:delete', '#', 103, 1, sysdate(), null, null, '');
