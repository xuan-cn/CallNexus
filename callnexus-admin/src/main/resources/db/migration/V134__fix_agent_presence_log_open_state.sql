DELETE duplicate_log
FROM cc_agent_presence_log duplicate_log
JOIN cc_agent_presence_log current_log
  ON current_log.tenant_id = duplicate_log.tenant_id
 AND current_log.agent_id = duplicate_log.agent_id
 AND current_log.status = duplicate_log.status
 AND current_log.started_at = duplicate_log.started_at
 AND current_log.ended_at IS NULL
 AND current_log.id <> duplicate_log.id
WHERE duplicate_log.duration_seconds = 0
  AND duplicate_log.ended_at = duplicate_log.started_at;

DELETE older_open
FROM cc_agent_presence_log older_open
JOIN (
    SELECT tenant_id, agent_id, MAX(id) AS keep_id
    FROM cc_agent_presence_log
    WHERE ended_at IS NULL
    GROUP BY tenant_id, agent_id
    HAVING COUNT(*) > 1
) duplicated
  ON duplicated.tenant_id = older_open.tenant_id
 AND duplicated.agent_id = older_open.agent_id
 AND duplicated.keep_id <> older_open.id
WHERE older_open.ended_at IS NULL;

SET @presence_open_column_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'cc_agent_presence_log'
      AND column_name = 'open_agent_id'
);
SET @presence_open_column_sql := IF(
    @presence_open_column_exists = 0,
    'ALTER TABLE cc_agent_presence_log ADD COLUMN open_agent_id BIGINT GENERATED ALWAYS AS (CASE WHEN ended_at IS NULL THEN agent_id ELSE NULL END) STORED COMMENT ''未结束状态唯一键''',
    'SELECT 1'
);
PREPARE presence_open_column_stmt FROM @presence_open_column_sql;
EXECUTE presence_open_column_stmt;
DEALLOCATE PREPARE presence_open_column_stmt;

SET @presence_open_index_exists := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'cc_agent_presence_log'
      AND index_name = 'uk_presence_log_one_open'
);
SET @presence_open_index_sql := IF(
    @presence_open_index_exists = 0,
    'ALTER TABLE cc_agent_presence_log ADD UNIQUE KEY uk_presence_log_one_open (tenant_id, open_agent_id)',
    'SELECT 1'
);
PREPARE presence_open_index_stmt FROM @presence_open_index_sql;
EXECUTE presence_open_index_stmt;
DEALLOCATE PREPARE presence_open_index_stmt;
