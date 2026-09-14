ALTER TABLE cc_form_field
    ADD COLUMN import_enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否允许资料导入映射' AFTER field_remark,
    ADD COLUMN export_enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否允许业务数据导出' AFTER import_enabled,
    ADD COLUMN ai_fill_enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否允许AI自动填写' AFTER export_enabled,
    ADD COLUMN dial_enabled TINYINT NOT NULL DEFAULT 0 COMMENT '是否允许作为电话号码点击拨号' AFTER ai_fill_enabled;
