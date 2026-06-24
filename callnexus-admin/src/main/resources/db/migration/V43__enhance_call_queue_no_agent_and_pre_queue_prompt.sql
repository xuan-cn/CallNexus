ALTER TABLE cc_call_queue
    ADD COLUMN force_wait_media_id BIGINT NULL COMMENT '入队前强制播放提示音媒体ID' AFTER force_wait_seconds,
    ADD COLUMN no_agent_wait_seconds INT NOT NULL DEFAULT 5 COMMENT '无可用坐席时等待秒数，0表示继续等待' AFTER no_agent_target;
