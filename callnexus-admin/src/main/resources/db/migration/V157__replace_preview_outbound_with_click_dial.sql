-- 点击拨号改为基于客户归属的坐席工作台，不再创建预览外呼任务。

ALTER TABLE cc_customer_assignment
    ADD COLUMN dial_status VARCHAR(16) NOT NULL DEFAULT 'UNCALLED'
        COMMENT '拨号工作状态：UNCALLED未拨打、PROCESSING待处理、FOLLOW_UP需要跟进、COMPLETED已完成' AFTER enabled,
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 0 COMMENT '当前归属周期拨打次数' AFTER dial_status,
    ADD COLUMN last_called_at DATETIME NULL COMMENT '最后拨打时间' AFTER attempt_count,
    ADD COLUMN last_result_code VARCHAR(32) NULL COMMENT '最后拨打结果' AFTER last_called_at,
    ADD COLUMN last_result_remark VARCHAR(1000) NULL COMMENT '最后处理备注' AFTER last_result_code,
    ADD COLUMN next_follow_up_at DATETIME NULL COMMENT '下次跟进时间' AFTER last_result_remark,
    ADD COLUMN completed_at DATETIME NULL COMMENT '完成时间' AFTER next_follow_up_at,
    ADD KEY idx_cc_customer_assignment_dial_status (tenant_id, agent_id, enabled, dial_status),
    ADD KEY idx_cc_customer_assignment_follow_up (tenant_id, agent_id, enabled, next_follow_up_at);

CREATE TABLE cc_customer_dial_attempt (
    id BIGINT NOT NULL COMMENT '点击拨号明细ID',
    tenant_id VARCHAR(20) NOT NULL COMMENT '租户ID',
    assignment_id BIGINT NOT NULL COMMENT '客户归属ID',
    customer_id BIGINT NOT NULL COMMENT '客户ID',
    agent_id BIGINT NOT NULL COMMENT '坐席ID',
    user_id BIGINT NOT NULL COMMENT '系统用户ID',
    phone_id BIGINT NULL COMMENT '客户号码ID',
    phone_number VARCHAR(32) NOT NULL COMMENT '拨打号码快照',
    attempt_no INT NOT NULL COMMENT '当前归属周期第几次拨打',
    business_call_id VARCHAR(64) NOT NULL COMMENT '业务通话ID',
    status VARCHAR(16) NOT NULL DEFAULT 'DIALING' COMMENT '状态：DIALING、ANSWERED、ENDED',
    result_code VARCHAR(32) NULL COMMENT '坐席登记结果',
    result_remark VARCHAR(1000) NULL COMMENT '坐席处理备注',
    suggested_result_code VARCHAR(32) NULL COMMENT '系统建议结果',
    started_at DATETIME NOT NULL COMMENT '开始拨打时间',
    answered_at DATETIME NULL COMMENT '客户接听时间',
    ended_at DATETIME NULL COMMENT '通话结束时间',
    duration_seconds INT NOT NULL DEFAULT 0 COMMENT '通话总时长',
    billable_seconds INT NOT NULL DEFAULT 0 COMMENT '接通时长',
    hangup_cause VARCHAR(64) NULL COMMENT '挂机原因',
    create_dept BIGINT NULL,
    create_by BIGINT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by BIGINT NULL,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    version INT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_customer_dial_attempt_call (tenant_id, business_call_id, deleted),
    KEY idx_cc_customer_dial_attempt_assignment (tenant_id, assignment_id, started_at),
    KEY idx_cc_customer_dial_attempt_agent (tenant_id, agent_id, started_at),
    KEY idx_cc_customer_dial_attempt_customer (tenant_id, customer_id, started_at)
) ENGINE=InnoDB COMMENT='客户点击拨号明细';

-- 清除旧点击拨号任务数据。自动外呼 task_type=AUTO 不受影响。
CREATE TEMPORARY TABLE tmp_preview_outbound_task_ids (
    id BIGINT NOT NULL PRIMARY KEY
) ENGINE=MEMORY;

INSERT INTO tmp_preview_outbound_task_ids (id)
SELECT id FROM cc_outbound_task WHERE task_type = 'PREVIEW';

UPDATE cc_call_session session
JOIN tmp_preview_outbound_task_ids preview_task ON preview_task.id = session.outbound_task_id
SET session.outbound_task_id = NULL,
    session.outbound_member_id = NULL;

DELETE dispatch FROM cc_auto_outbound_dispatch dispatch
JOIN tmp_preview_outbound_task_ids preview_task ON preview_task.id = dispatch.task_id;

DELETE follow_up FROM cc_customer_follow_up follow_up
JOIN cc_outbound_attempt attempt ON attempt.id = follow_up.source_id
JOIN tmp_preview_outbound_task_ids preview_task ON preview_task.id = attempt.task_id
WHERE follow_up.source_type = 'AUTO_OUTBOUND';

DELETE attempt FROM cc_outbound_attempt attempt
JOIN tmp_preview_outbound_task_ids preview_task ON preview_task.id = attempt.task_id;

DELETE import_row FROM cc_outbound_import_row import_row
JOIN cc_outbound_import_batch batch ON batch.id = import_row.batch_id
JOIN tmp_preview_outbound_task_ids preview_task ON preview_task.id = batch.task_id;

DELETE batch FROM cc_outbound_import_batch batch
JOIN tmp_preview_outbound_task_ids preview_task ON preview_task.id = batch.task_id;

DELETE blacklist_row FROM cc_outbound_blacklist_import_row blacklist_row
JOIN cc_outbound_blacklist_import_batch batch ON batch.id = blacklist_row.batch_id
JOIN tmp_preview_outbound_task_ids preview_task ON preview_task.id = batch.task_id;

DELETE batch FROM cc_outbound_blacklist_import_batch batch
JOIN tmp_preview_outbound_task_ids preview_task ON preview_task.id = batch.task_id;

DELETE blacklist FROM cc_outbound_blacklist blacklist
JOIN tmp_preview_outbound_task_ids preview_task ON preview_task.id = blacklist.task_id;

DELETE source FROM cc_outbound_task_source source
JOIN tmp_preview_outbound_task_ids preview_task ON preview_task.id = source.task_id;

DELETE retry_rule FROM cc_outbound_task_retry_rule retry_rule
JOIN tmp_preview_outbound_task_ids preview_task ON preview_task.id = retry_rule.task_id;

DELETE call_window FROM cc_outbound_task_call_window call_window
JOIN tmp_preview_outbound_task_ids preview_task ON preview_task.id = call_window.task_id;

DELETE member FROM cc_outbound_member member
JOIN tmp_preview_outbound_task_ids preview_task ON preview_task.id = member.task_id;

DELETE task FROM cc_outbound_task task
JOIN tmp_preview_outbound_task_ids preview_task ON preview_task.id = task.id;

DROP TEMPORARY TABLE tmp_preview_outbound_task_ids;

UPDATE sys_menu
SET menu_name = '点击拨号',
    component = 'callcenter/outbound-task/index',
    perms = 'callcenter:click-dial:list',
    remark = '展示当前坐席被分配客户并直接点击拨号'
WHERE menu_id = 9150;

UPDATE sys_menu SET menu_name = '点击拨号执行', perms = 'callcenter:click-dial:execute' WHERE menu_id = 9151;
UPDATE sys_menu SET menu_name = '拨号结果登记', perms = 'callcenter:click-dial:result' WHERE menu_id = 9152;

DELETE FROM sys_role_menu WHERE menu_id IN (9153, 9154, 9155);
DELETE FROM sys_menu WHERE menu_id IN (9153, 9154, 9155);
