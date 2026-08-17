-- 号码管理不再承担呼入路由。DID、端口、账号和 Header 路由统一由 cc_inbound_did_entry 管理。
-- 工作时间配置暂时保留，供 DID 入口中的“工作时间路由”目标继续引用。
UPDATE cc_phone_number
SET route_type = 'NONE',
    route_target = NULL
WHERE route_type IN ('EXTENSION', 'IVR', 'QUEUE', 'VOICEMAIL');
