ALTER TABLE cc_form_field
    ADD COLUMN query_enabled TINYINT NOT NULL DEFAULT 0 COMMENT '是否允许作为列表查询条件' AFTER list_visible,
    ADD COLUMN query_mode VARCHAR(16) NULL COMMENT '查询方式：AUTO/EQ/LIKE/BETWEEN/CONTAINS_ANY' AFTER query_enabled,
    ADD COLUMN query_order INT NOT NULL DEFAULT 0 COMMENT '查询区域显示顺序' AFTER query_mode,
    ADD COLUMN field_remark VARCHAR(500) NULL COMMENT '字段业务说明' AFTER query_order;
