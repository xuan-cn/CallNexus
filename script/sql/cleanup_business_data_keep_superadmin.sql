-- CallNexus MySQL 业务数据清理脚本
-- 目标：清空全部 CallNexus 业务数据及用户配置，仅保留内置基础数据、user_id=1 和 role_id=1。
-- 注意：应用代码硬编码 user_id=1 为超级管理员，不能改成其他用户 ID。
-- 执行前必须停止 CallNexus、SnailJob、media-agent 及 FreeSWITCH 事件消费，并完成数据库备份。
-- TRUNCATE 会隐式提交，执行后不能通过 ROLLBACK 恢复。

-- 完成备份并确认下面查询结果后，将 NO 改为 YES，再执行完整脚本。
SET @confirm_cleanup = 'NO';

-- 第一步：先单独执行并人工确认，user_id=1 必须是要保留的“callnexus管理员”。
SELECT user_id, tenant_id, user_name, nick_name, status, del_flag
FROM sys_user
WHERE user_id = 1;

SELECT role_id, tenant_id, role_name, role_key, status, del_flag
FROM sys_role
WHERE role_id = 1;

DROP PROCEDURE IF EXISTS cleanup_callnexus_business_data;
DELIMITER $$

CREATE PROCEDURE cleanup_callnexus_business_data()
BEGIN
    DECLARE finished INT DEFAULT 0;
    DECLARE current_table VARCHAR(128);
    DECLARE table_cursor CURSOR FOR
        SELECT table_name
        FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_type = 'BASE TABLE'
          AND (
            -- 清空所有业务域表。以下三张是内置定义/号码基础库，不属于用户业务数据。
            (LEFT(table_name, 3) = 'cc_' AND table_name NOT IN (
                'cc_callcenter_config_definition', 'cc_area_code', 'cc_mobile_number_segment'
            ))

            -- 清空流程定义、分类和全部流程实例，保留系统内置 SpEL 组件定义。
            OR (LEFT(table_name, 5) = 'flow_' AND table_name <> 'flow_spel')

            -- SnailJob 仅清运行历史，保留调度任务、重试场景、通知和节点配置。
            OR table_name IN (
                'sj_job_log_message', 'sj_job_summary', 'sj_job_task', 'sj_job_task_batch',
                'sj_retry', 'sj_retry_dead_letter', 'sj_retry_summary', 'sj_retry_task',
                'sj_retry_task_log_message', 'sj_workflow_task_batch',

                -- 系统运行日志及演示数据
                'sys_oper_log', 'sys_logininfor', 'sys_notice', 'sys_social',
                'gen_table', 'gen_table_column', 'test_demo', 'test_tree', 'test_leave'
            )
          )
        ORDER BY table_name;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET finished = 1;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        SET FOREIGN_KEY_CHECKS = 1;
        RESIGNAL;
    END;

    IF COALESCE(@confirm_cleanup, 'NO') <> 'YES' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '终止清理：请先备份并将 @confirm_cleanup 设置为 YES';
    END IF;

    IF (SELECT COUNT(*) FROM sys_user WHERE user_id = 1 AND del_flag = '0') <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '终止清理：不存在有效的 user_id=1 超级管理员';
    END IF;
    IF (SELECT COUNT(*) FROM sys_role WHERE role_id = 1 AND role_key = 'superadmin' AND del_flag = '0') <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '终止清理：不存在有效的 role_id=1 / superadmin 角色';
    END IF;

    SET FOREIGN_KEY_CHECKS = 0;

    OPEN table_cursor;
    truncate_loop: LOOP
        FETCH table_cursor INTO current_table;
        IF finished = 1 THEN
            LEAVE truncate_loop;
        END IF;
        SET @truncate_sql = CONCAT('TRUNCATE TABLE `', REPLACE(current_table, '`', '``'), '`');
        PREPARE truncate_statement FROM @truncate_sql;
        EXECUTE truncate_statement;
        DEALLOCATE PREPARE truncate_statement;
    END LOOP;
    CLOSE table_cursor;

    -- 清理用户、角色关联，仅恢复 user_id=1 与 role_id=1 的关系。
    DELETE FROM sys_user_role;
    INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 1);
    DELETE FROM sys_user_post WHERE user_id <> 1;
    DELETE FROM sys_role_dept WHERE role_id <> 1;
    DELETE FROM sys_role_menu WHERE role_id <> 1;
    UPDATE sys_dept SET leader = NULL WHERE leader IS NOT NULL AND leader <> 1;
    DELETE FROM sys_user WHERE user_id <> 1;
    DELETE FROM sys_role WHERE role_id <> 1;

    -- OSS 仅保留管理员头像元数据；数据库清理不会删除对象存储中的文件本体。
    DELETE FROM sys_oss
    WHERE oss_id NOT IN (
        SELECT avatar FROM sys_user WHERE user_id = 1 AND avatar IS NOT NULL
    );

    SET FOREIGN_KEY_CHECKS = 1;
END$$

DELIMITER ;

CALL cleanup_callnexus_business_data();
DROP PROCEDURE cleanup_callnexus_business_data;

-- 执行后校验：应各返回 1 行，且映射为 1 -> 1。
SELECT user_id, tenant_id, user_name, nick_name, status, del_flag FROM sys_user;
SELECT role_id, tenant_id, role_name, role_key, status, del_flag FROM sys_role;
SELECT user_id, role_id FROM sys_user_role;

-- 检查是否还有业务运行表残留数据；结果应全部为 0。
SELECT
    (SELECT COUNT(*) FROM cc_customer) AS customer_count,
    (SELECT COUNT(*) FROM cc_ticket) AS ticket_count,
    (SELECT COUNT(*) FROM cc_call_session) AS call_count,
    (SELECT COUNT(*) FROM cc_agent) AS agent_count,
    (SELECT COUNT(*) FROM cc_sip_account) AS sip_account_count,
    (SELECT COUNT(*) FROM cc_skill_group) AS skill_group_count,
    (SELECT COUNT(*) FROM cc_freeswitch_node) AS freeswitch_node_count,
    (SELECT COUNT(*) FROM cc_ai_knowledge_base) AS knowledge_base_count,
    (SELECT COUNT(*) FROM cc_outbound_member) AS outbound_member_count,
    (SELECT COUNT(*) FROM cc_chat_conversation) AS chat_count,
    (SELECT COUNT(*) FROM cc_quality_task) AS quality_task_count,
    (SELECT COUNT(*) FROM flow_instance) AS workflow_instance_count;
