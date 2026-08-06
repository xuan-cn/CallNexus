-- 独立会议支持保存第三方系统可读的会议名称；FreeSWITCH 房间名仍使用 conference_name。
ALTER TABLE cc_call_conference
    ADD COLUMN display_name VARCHAR(128) NULL COMMENT '会议显示名称' AFTER conference_name;
