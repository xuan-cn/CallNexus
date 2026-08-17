-- 支持外呼伴侣等设备向 FreeSWITCH 注册，外呼时使用设备的动态 Contact。
ALTER TABLE cc_freeswitch_gateway
    ADD COLUMN access_mode VARCHAR(32) NOT NULL DEFAULT 'IP_TRUNK'
        COMMENT '接入模式：IP_TRUNK、OUTBOUND_REGISTER、DEVICE_REGISTER' AFTER direction,
    ADD COLUMN registered_identity VARCHAR(64) NULL
        COMMENT '设备注册身份' AFTER username,
    ADD COLUMN sip_profile VARCHAR(32) NOT NULL DEFAULT 'internal'
        COMMENT '设备注册使用的 Sofia Profile' AFTER registered_identity,
    MODIFY COLUMN proxy VARCHAR(128) NULL COMMENT '上游 SIP 地址，设备注册模式可为空';

UPDATE cc_freeswitch_gateway
SET access_mode = CASE WHEN register_enabled = 1 THEN 'OUTBOUND_REGISTER' ELSE 'IP_TRUNK' END
WHERE access_mode IS NULL OR access_mode = '' OR access_mode = 'IP_TRUNK';

