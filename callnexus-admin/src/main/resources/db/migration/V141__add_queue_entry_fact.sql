CREATE TABLE IF NOT EXISTS cc_queue_entry_fact (
    id                         BIGINT       NOT NULL COMMENT '队列进入事实ID',
    tenant_id                  VARCHAR(20)  NOT NULL COMMENT '租户编号',
    session_id                 BIGINT       NOT NULL COMMENT '业务通话会话ID',
    business_call_id           VARCHAR(64)  NOT NULL COMMENT '业务通话ID',
    queue_in_event_id          BIGINT       NOT NULL COMMENT 'QUEUE_IN时间线事件ID，作为幂等键',
    queue_id                   BIGINT       NOT NULL COMMENT '队列ID',
    queue_code_snapshot        VARCHAR(64)  NULL COMMENT '进入时队列编码快照',
    queue_name_snapshot        VARCHAR(64)  NULL COMMENT '进入时队列名称快照',
    skill_group_id             BIGINT       NULL COMMENT '进入时技能组ID',
    skill_group_name_snapshot  VARCHAR(64)  NULL COMMENT '进入时技能组名称快照',
    node_id                    BIGINT       NULL COMMENT 'FreeSWITCH节点ID',
    customer_number            VARCHAR(64)  NULL COMMENT '客户号码',
    entry_channel_uuid         VARCHAR(64)  NULL COMMENT '进入队列的通话腿UUID',
    entered_at                 DATETIME     NOT NULL COMMENT '进入队列时间',
    answered_at                DATETIME     NULL COMMENT '坐席接听时间',
    ended_at                   DATETIME     NULL COMMENT '本次排队结束时间',
    outcome                    VARCHAR(32)  NOT NULL DEFAULT 'WAITING' COMMENT '结果：WAITING/ANSWERED/ABANDONED/TIMEOUT/ENDED',
    answer_agent_id            BIGINT       NULL COMMENT '接听坐席ID',
    answer_agent_name_snapshot VARCHAR(64)  NULL COMMENT '接听坐席名称快照',
    answer_agent_extension     VARCHAR(32)  NULL COMMENT '接听坐席分机',
    wait_seconds               BIGINT       NOT NULL DEFAULT 0 COMMENT '本次排队等待秒数',
    create_dept                BIGINT       NULL,
    create_by                  BIGINT       NULL,
    create_time                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by                  BIGINT       NULL,
    update_time                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_queue_entry_event (tenant_id, queue_in_event_id),
    KEY idx_queue_entry_queue_time (tenant_id, queue_id, entered_at),
    KEY idx_queue_entry_session_time (tenant_id, session_id, entered_at),
    KEY idx_queue_entry_outcome_time (tenant_id, outcome, entered_at),
    KEY idx_queue_entry_agent_time (tenant_id, answer_agent_id, answered_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='队列进入结构化事实';

-- 回填已有队列时间线。一个 QUEUE_IN 对应一条事实，后续首个终止事件决定本次排队结果。
INSERT IGNORE INTO cc_queue_entry_fact (
    id, tenant_id, session_id, business_call_id, queue_in_event_id,
    queue_id, queue_code_snapshot, queue_name_snapshot,
    skill_group_id, skill_group_name_snapshot, node_id,
    customer_number, entry_channel_uuid, entered_at,
    answered_at, ended_at, outcome,
    answer_agent_id, answer_agent_name_snapshot, answer_agent_extension,
    wait_seconds, create_time, update_time
)
SELECT
    qin.id,
    qin.tenant_id,
    qin.session_id,
    session.business_call_id,
    qin.id,
    CAST(JSON_UNQUOTE(JSON_EXTRACT(qin.metadata_json, '$.queueId')) AS UNSIGNED),
    COALESCE(JSON_UNQUOTE(JSON_EXTRACT(qin.metadata_json, '$.queueCode')), queue.queue_code),
    COALESCE(JSON_UNQUOTE(JSON_EXTRACT(qin.metadata_json, '$.queueName')), queue.queue_name),
    queue.skill_group_id,
    skill_group.group_name,
    COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(qin.metadata_json, '$.nodeId')) AS UNSIGNED), session.node_id),
    CASE WHEN session.direction = 'INBOUND' THEN session.caller_number ELSE session.called_number END,
    qin.channel_uuid,
    qin.occurred_at,
    CASE WHEN terminal.event_type = 'AGENT_ANSWER' THEN terminal.occurred_at ELSE NULL END,
    COALESCE(terminal.occurred_at, session.ended_at),
    CASE terminal.event_type
        WHEN 'AGENT_ANSWER' THEN 'ANSWERED'
        WHEN 'ABANDON' THEN 'ABANDONED'
        WHEN 'QUEUE_TIMEOUT' THEN 'TIMEOUT'
        ELSE CASE WHEN session.ended_at IS NULL THEN 'WAITING' ELSE 'ENDED' END
    END,
    CAST(JSON_UNQUOTE(JSON_EXTRACT(terminal.metadata_json, '$.agentId')) AS UNSIGNED),
    agent.agent_name,
    JSON_UNQUOTE(JSON_EXTRACT(terminal.metadata_json, '$.agentExtension')),
    GREATEST(0, TIMESTAMPDIFF(SECOND, qin.occurred_at, COALESCE(terminal.occurred_at, session.ended_at, qin.occurred_at))),
    qin.create_time,
    COALESCE(terminal.occurred_at, session.ended_at, qin.update_time)
FROM cc_call_event qin
JOIN cc_call_session session
  ON session.tenant_id = qin.tenant_id AND session.id = qin.session_id
LEFT JOIN cc_call_event terminal
  ON terminal.id = (
      SELECT event_after_entry.id
      FROM cc_call_event event_after_entry
      WHERE event_after_entry.tenant_id = qin.tenant_id
        AND event_after_entry.session_id = qin.session_id
        AND event_after_entry.event_type IN ('AGENT_ANSWER', 'ABANDON', 'QUEUE_TIMEOUT')
        AND (event_after_entry.occurred_at > qin.occurred_at
             OR (event_after_entry.occurred_at = qin.occurred_at AND event_after_entry.id > qin.id))
      ORDER BY event_after_entry.occurred_at ASC, event_after_entry.id ASC
      LIMIT 1
  )
LEFT JOIN cc_call_queue queue
  ON queue.tenant_id = qin.tenant_id
 AND queue.id = CAST(JSON_UNQUOTE(JSON_EXTRACT(qin.metadata_json, '$.queueId')) AS UNSIGNED)
LEFT JOIN cc_skill_group skill_group
  ON skill_group.tenant_id = queue.tenant_id AND skill_group.id = queue.skill_group_id
LEFT JOIN cc_agent agent
  ON agent.tenant_id = qin.tenant_id
 AND agent.id = CAST(JSON_UNQUOTE(JSON_EXTRACT(terminal.metadata_json, '$.agentId')) AS UNSIGNED)
WHERE qin.event_type = 'QUEUE_IN'
  AND JSON_EXTRACT(qin.metadata_json, '$.queueId') IS NOT NULL
  AND JSON_UNQUOTE(JSON_EXTRACT(qin.metadata_json, '$.queueId')) NOT IN ('', 'null');
