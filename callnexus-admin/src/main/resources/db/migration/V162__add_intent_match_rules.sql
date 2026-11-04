ALTER TABLE cc_ai_intent
    ADD COLUMN match_rules_json MEDIUMTEXT NULL COMMENT '可视化正反例匹配规则' AFTER description;
