-- 线路级拨号规范。作为所有经该线路外呼时的默认号码处理规则。
ALTER TABLE cc_freeswitch_gateway
    ADD COLUMN local_area_code VARCHAR(16) NULL COMMENT '本地区号，例如0451' AFTER caller_id_number,
    ADD COLUMN add_local_area_code TINYINT NOT NULL DEFAULT 0 COMMENT '本地固话是否补本地区号' AFTER local_area_code,
    ADD COLUMN add_missing_area_code_zero TINYINT NOT NULL DEFAULT 0 COMMENT '异地固话区号缺0时是否补0' AFTER add_local_area_code,
    ADD COLUMN strip_china_country_code TINYINT NOT NULL DEFAULT 0 COMMENT '是否去除中国国家码' AFTER add_missing_area_code_zero,
    ADD COLUMN outbound_prefix VARCHAR(16) NULL COMMENT '出局前缀' AFTER strip_china_country_code;
