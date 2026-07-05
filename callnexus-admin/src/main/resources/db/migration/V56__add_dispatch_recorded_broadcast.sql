ALTER TABLE cc_dispatch_call_task
    MODIFY operator_leg_uuid VARCHAR(64) NULL COMMENT '调度分机电话腿UUID，预录音广播为空',
    MODIFY conference_name VARCHAR(128) NULL COMMENT 'FreeSWITCH会议名称，预录音广播为空',
    ADD COLUMN media_asset_id BIGINT NULL COMMENT '广播声音媒体ID' AFTER conference_name,
    ADD COLUMN media_name VARCHAR(255) NULL COMMENT '广播声音媒体名称快照' AFTER media_asset_id,
    ADD COLUMN media_path VARCHAR(500) NULL COMMENT '广播声音在目标节点的本地路径快照' AFTER media_name;

ALTER TABLE cc_dispatch_call_task
    MODIFY task_type VARCHAR(16) NOT NULL COMMENT '任务类型：SINGLE单呼、GROUP组呼、BROADCAST预录音广播';

INSERT IGNORE INTO sys_menu VALUES('9314', '调度预录音广播', '9300', '14', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:dispatch-control:broadcast', '#', 103, 1, SYSDATE(), NULL, NULL, '允许调度员向一个或多个SIP分机播放预录音广播');
INSERT IGNORE INTO sys_menu VALUES('9315', '终止调度广播', '9300', '15', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:dispatch-control:stop-broadcast', '#', 103, 1, SYSDATE(), NULL, NULL, '允许调度员终止正在播放的预录音广播');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9314'
FROM sys_role_menu
WHERE menu_id IN ('9300', '9301');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, '9315'
FROM sys_role_menu
WHERE menu_id IN ('9300', '9301');
