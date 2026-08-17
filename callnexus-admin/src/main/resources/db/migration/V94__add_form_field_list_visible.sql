ALTER TABLE cc_form_field
    ADD COLUMN list_visible TINYINT NOT NULL DEFAULT 0 COMMENT '是否在列表显示' AFTER validation_rules;
