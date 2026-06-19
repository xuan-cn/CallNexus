ALTER TABLE cc_call_queue
    ADD COLUMN mask_caller_number TINYINT NOT NULL DEFAULT 0 COMMENT '是否隐藏来电号码' AFTER caller_number_id,
    ADD COLUMN manual_answer TINYINT NOT NULL DEFAULT 0 COMMENT '是否要求坐席手动接听，当前为配置预留' AFTER mask_caller_number,
    ADD COLUMN busy_transfer_mobile TINYINT NOT NULL DEFAULT 0 COMMENT '遇忙是否转手机，当前为配置预留' AFTER manual_answer,
    ADD COLUMN busy_transfer_number VARCHAR(32) NULL COMMENT '遇忙转手机号码' AFTER busy_transfer_mobile,
    ADD COLUMN force_wait_seconds INT NOT NULL DEFAULT 0 COMMENT '进入队列前强制等待秒数' AFTER busy_transfer_number,
    ADD COLUMN answer_action VARCHAR(32) NOT NULL DEFAULT 'NONE' COMMENT '接通时动作：NONE无动作、PLAY_AGENT_NUMBER播报工号、PLAY_MEDIA播放语音' AFTER force_wait_seconds,
    ADD COLUMN answer_media_id BIGINT NULL COMMENT '接通时播放语音媒体ID' AFTER answer_action,
    ADD COLUMN hangup_key_action VARCHAR(32) NOT NULL DEFAULT 'NONE' COMMENT '挂机按键采集：NONE不采集、AGENT采集坐席按键、CALLER采集客户按键，当前为配置预留' AFTER answer_media_id,
    ADD COLUMN timeout_action VARCHAR(32) NOT NULL DEFAULT 'HANGUP' COMMENT '队列超时处理：HANGUP挂机、CONTINUE继续等待、VOICEMAIL语音留言、IVR转IVR、EXTENSION转分机、QUEUE转队列' AFTER hangup_key_action,
    ADD COLUMN timeout_target VARCHAR(64) NULL COMMENT '队列超时处理目标' AFTER timeout_action,
    ADD COLUMN no_agent_action VARCHAR(32) NOT NULL DEFAULT 'WAIT' COMMENT '无可用坐席处理：WAIT继续等待、HANGUP挂机、VOICEMAIL语音留言、IVR转IVR、EXTENSION转分机、QUEUE转队列' AFTER timeout_target,
    ADD COLUMN no_agent_target VARCHAR(64) NULL COMMENT '无可用坐席处理目标' AFTER no_agent_action,
    ADD COLUMN agent_no_answer_action VARCHAR(32) NOT NULL DEFAULT 'NEXT_AGENT' COMMENT '坐席未接处理：NEXT_AGENT继续找下一个坐席、BREAK_AGENT坐席置忙/暂停分配' AFTER no_agent_target,
    ADD COLUMN agent_timeout_transfer_mobile TINYINT NOT NULL DEFAULT 0 COMMENT '坐席振铃超时是否转手机，当前为配置预留' AFTER agent_no_answer_action,
    ADD COLUMN agent_timeout_transfer_number VARCHAR(32) NULL COMMENT '坐席振铃超时转手机号码' AFTER agent_timeout_transfer_mobile,
    ADD COLUMN sticky_agent_enabled TINYINT NOT NULL DEFAULT 0 COMMENT '是否启用记忆坐席，当前为配置预留' AFTER agent_timeout_transfer_number,
    ADD COLUMN queue_announce_enabled TINYINT NOT NULL DEFAULT 0 COMMENT '是否启用排队提醒' AFTER sticky_agent_enabled,
    ADD COLUMN queue_announce_interval INT NOT NULL DEFAULT 30 COMMENT '排队提醒间隔秒数' AFTER queue_announce_enabled,
    ADD COLUMN queue_announce_media_id BIGINT NULL COMMENT '排队提醒语音媒体ID' AFTER queue_announce_interval;

CREATE INDEX idx_cc_call_queue_timeout_target ON cc_call_queue (tenant_id, timeout_action, timeout_target);
CREATE INDEX idx_cc_call_queue_no_agent_target ON cc_call_queue (tenant_id, no_agent_action, no_agent_target);
