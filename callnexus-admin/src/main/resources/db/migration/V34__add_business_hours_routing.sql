CREATE TABLE cc_business_hours_plan (
    id BIGINT NOT NULL COMMENT '工作时间方案ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    plan_code VARCHAR(64) NOT NULL COMMENT '方案编码',
    plan_name VARCHAR(128) NOT NULL COMMENT '方案名称',
    timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai' COMMENT 'IANA时区',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    remark VARCHAR(500) NULL COMMENT '备注',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_business_hours_plan_code (tenant_id, plan_code, deleted)
) ENGINE=InnoDB COMMENT='工作时间方案';

CREATE TABLE cc_business_hours_period (
    id BIGINT NOT NULL COMMENT '工作时间段ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    plan_id BIGINT NOT NULL COMMENT '工作时间方案ID',
    day_of_week TINYINT NOT NULL COMMENT '星期，1星期一至7星期日',
    start_time TIME NOT NULL COMMENT '开始时间',
    end_time TIME NOT NULL COMMENT '结束时间',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    KEY idx_cc_business_hours_period_plan (tenant_id, plan_id, day_of_week)
) ENGINE=InnoDB COMMENT='工作时间周周期';

CREATE TABLE cc_business_hours_exception (
    id BIGINT NOT NULL COMMENT '工作时间特殊日期ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    plan_id BIGINT NOT NULL COMMENT '工作时间方案ID',
    exception_date DATE NOT NULL COMMENT '特殊日期',
    exception_type VARCHAR(16) NOT NULL COMMENT '类型：CLOSED全天休息、CUSTOM自定义时段',
    start_time TIME NULL COMMENT '自定义开始时间',
    end_time TIME NULL COMMENT '自定义结束时间',
    description VARCHAR(255) NULL COMMENT '说明',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    KEY idx_cc_business_hours_exception_plan_date (tenant_id, plan_id, exception_date)
) ENGINE=InnoDB COMMENT='工作时间特殊日期';

CREATE TABLE cc_phone_business_hours_route (
    id BIGINT NOT NULL COMMENT '号码工作时间路由ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    phone_number_id BIGINT NOT NULL COMMENT '号码ID',
    plan_id BIGINT NOT NULL COMMENT '工作时间方案ID',
    in_hours_target_type VARCHAR(16) NOT NULL COMMENT '工作时间内目标类型',
    in_hours_target VARCHAR(64) NULL COMMENT '工作时间内目标',
    out_hours_target_type VARCHAR(16) NOT NULL COMMENT '工作时间外目标类型',
    out_hours_target VARCHAR(64) NULL COMMENT '工作时间外目标',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_phone_business_hours_route_phone (tenant_id, phone_number_id, deleted),
    KEY idx_cc_phone_business_hours_route_plan (tenant_id, plan_id)
) ENGINE=InnoDB COMMENT='号码工作时间路由';

INSERT INTO sys_menu VALUES('9250', '工作时间', '9000', '25', 'business-hours', 'callcenter/business-hours/index', '', 1, 0, 'C', '0', '0', 'callcenter:business-hours:list', 'clock', 103, 1, sysdate(), null, null, '工作时间方案管理菜单');
INSERT INTO sys_menu VALUES('9251', '工作时间查询', '9250', '1', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:business-hours:query', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9252', '工作时间新增', '9250', '2', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:business-hours:create', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9253', '工作时间修改', '9250', '3', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:business-hours:update', '#', 103, 1, sysdate(), null, null, '');
INSERT INTO sys_menu VALUES('9254', '工作时间删除', '9250', '4', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:business-hours:delete', '#', 103, 1, sysdate(), null, null, '');
