ALTER TABLE cc_freeswitch_node
    ADD COLUMN sip_profile_name VARCHAR(32) NOT NULL DEFAULT 'external' COMMENT 'SIP Profile 名称，例如 external'
        AFTER media_root_path,
    ADD COLUMN sip_ip VARCHAR(128) NOT NULL DEFAULT '$${local_ip_v4}' COMMENT 'SIP 监听地址，可填写固定 IP 或 FreeSWITCH 变量'
        AFTER sip_profile_name,
    ADD COLUMN rtp_ip VARCHAR(128) NOT NULL DEFAULT '$${local_ip_v4}' COMMENT 'RTP 监听地址，可填写固定 IP 或 FreeSWITCH 变量'
        AFTER sip_ip,
    ADD COLUMN auto_nat_enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否使用 auto-nat 作为对外 SIP/RTP 地址'
        AFTER rtp_ip,
    ADD COLUMN ext_sip_ip VARCHAR(128) NULL COMMENT '对外 SIP 地址，关闭 auto-nat 时使用'
        AFTER auto_nat_enabled,
    ADD COLUMN ext_rtp_ip VARCHAR(128) NULL COMMENT '对外 RTP 地址，关闭 auto-nat 时使用'
        AFTER ext_sip_ip;
