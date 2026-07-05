ALTER TABLE cc_dispatch_call_task
    ADD COLUMN intercom_talking TINYINT NOT NULL DEFAULT 0 COMMENT '对讲调度分机是否正在发言' AFTER media_path;

ALTER TABLE cc_dispatch_call_task
    MODIFY task_type VARCHAR(16) NOT NULL COMMENT '任务类型：SINGLE单呼、GROUP组呼、BROADCAST预录音广播、INTERCOM单目标对讲';

INSERT IGNORE INTO sys_menu VALUES('9316', '发起调度对讲', '9300', '16', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:dispatch-control:intercom', '#', 103, 1, SYSDATE(), NULL, NULL, '允许调度员向单个SIP分机发起半双工对讲');
INSERT IGNORE INTO sys_menu VALUES('9317', '控制对讲发言', '9300', '17', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:dispatch-control:intercom-talk', '#', 103, 1, SYSDATE(), NULL, NULL, '允许调度员按住说话和松开静音');
INSERT IGNORE INTO sys_menu VALUES('9318', '终止调度对讲', '9300', '18', '', '', '', 1, 0, 'F', '0', '0', 'callcenter:dispatch-control:stop-intercom', '#', 103, 1, SYSDATE(), NULL, NULL, '允许调度员结束对讲任务');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, permission.menu_id
FROM sys_role_menu source
CROSS JOIN (
    SELECT '9316' AS menu_id
    UNION ALL SELECT '9317'
    UNION ALL SELECT '9318'
) permission
WHERE source.menu_id IN ('9300', '9301');
