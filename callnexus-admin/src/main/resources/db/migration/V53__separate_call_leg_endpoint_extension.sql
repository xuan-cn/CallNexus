ALTER TABLE cc_call_leg
    ADD COLUMN endpoint_extension VARCHAR(32) NULL COMMENT '当前电话腿对应的SIP终端分机，与坐席绑定关系无关' AFTER leg_role,
    ADD KEY idx_cc_call_leg_endpoint (tenant_id, node_id, endpoint_extension, active);

ALTER TABLE cc_call_leg
    MODIFY COLUMN leg_role VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN'
        COMMENT '电话腿角色：CUSTOMER客户、EXTENSION未绑定坐席分机、AGENT坐席、CONSULT_AGENT咨询坐席、PICKUP强接坐席、MONITOR监听、WHISPER耳语、BARGE强插、SYSTEM系统、UNKNOWN未知',
    MODIFY COLUMN leg_state VARCHAR(32) NOT NULL DEFAULT 'CREATED'
        COMMENT '电话腿状态：CREATED创建、DIALING呼出中、RINGING被叫振铃、ANSWERED接听、BRIDGED桥接、HELD保持、PARKED驻留、ENDED结束';
