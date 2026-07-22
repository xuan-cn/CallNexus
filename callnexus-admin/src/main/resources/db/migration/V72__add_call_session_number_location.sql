ALTER TABLE cc_call_session
    ADD COLUMN caller_number_type VARCHAR(32) NULL COMMENT '主叫号码类型：MOBILE、LANDLINE、INTERNATIONAL、UNKNOWN' AFTER caller_number,
    ADD COLUMN caller_mobile_segment VARCHAR(16) NULL COMMENT '主叫手机号段' AFTER caller_number_type,
    ADD COLUMN caller_province VARCHAR(64) NULL COMMENT '主叫归属省份' AFTER caller_mobile_segment,
    ADD COLUMN caller_city VARCHAR(64) NULL COMMENT '主叫归属城市' AFTER caller_province,
    ADD COLUMN caller_carrier VARCHAR(64) NULL COMMENT '主叫运营商' AFTER caller_city,
    ADD COLUMN called_number_type VARCHAR(32) NULL COMMENT '被叫号码类型：MOBILE、LANDLINE、INTERNATIONAL、UNKNOWN' AFTER called_number,
    ADD COLUMN called_mobile_segment VARCHAR(16) NULL COMMENT '被叫手机号段' AFTER called_number_type,
    ADD COLUMN called_province VARCHAR(64) NULL COMMENT '被叫归属省份' AFTER called_mobile_segment,
    ADD COLUMN called_city VARCHAR(64) NULL COMMENT '被叫归属城市' AFTER called_province,
    ADD COLUMN called_carrier VARCHAR(64) NULL COMMENT '被叫运营商' AFTER called_city;
