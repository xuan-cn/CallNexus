CREATE TABLE cc_call_session (
    id                  BIGINT          NOT NULL COMMENT 'Business call session ID',
    tenant_id           VARCHAR(20)     NOT NULL COMMENT 'Tenant ID',
    business_call_id    VARCHAR(64)     NOT NULL COMMENT 'Stable business call ID',
    node_id             BIGINT          NOT NULL COMMENT 'FreeSWITCH node ID',
    direction           VARCHAR(16)     NOT NULL DEFAULT 'UNKNOWN' COMMENT 'INBOUND/OUTBOUND/INTERNAL/UNKNOWN',
    caller_number       VARCHAR(64)     NULL COMMENT 'Original caller number',
    called_number       VARCHAR(64)     NULL COMMENT 'Original called number',
    agent_id            BIGINT          NULL COMMENT 'Primary agent ID',
    agent_extension     VARCHAR(32)     NULL COMMENT 'Primary agent extension',
    call_status         VARCHAR(16)     NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/RINGING/ANSWERED/BRIDGED/ENDED',
    started_at          DATETIME        NULL COMMENT 'Business call start time',
    ringing_at          DATETIME        NULL COMMENT 'First ringing time',
    answered_at         DATETIME        NULL COMMENT 'First answer time',
    ended_at            DATETIME        NULL COMMENT 'Business call end time',
    duration_seconds    INT             NOT NULL DEFAULT 0 COMMENT 'Total duration seconds',
    billable_seconds    INT             NOT NULL DEFAULT 0 COMMENT 'Answered duration seconds',
    hangup_cause        VARCHAR(64)     NULL COMMENT 'Final hangup cause',
    create_dept         BIGINT          NULL,
    create_by           BIGINT          NULL,
    create_time         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by           BIGINT          NULL,
    update_time         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    version             INT             NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_call_session_business (tenant_id, business_call_id),
    KEY idx_cc_call_session_participant (tenant_id, caller_number, called_number),
    KEY idx_cc_call_session_started (tenant_id, started_at)
) ENGINE=InnoDB COMMENT='Business call session';

ALTER TABLE cc_call_record
    ADD COLUMN session_id BIGINT NULL COMMENT 'Related business call session ID' AFTER id,
    ADD KEY idx_cc_call_record_session (tenant_id, session_id, started_at);

CREATE TABLE cc_call_event (
    id                  BIGINT          NOT NULL COMMENT 'Call event ID',
    tenant_id           VARCHAR(20)     NOT NULL COMMENT 'Tenant ID',
    session_id          BIGINT          NOT NULL COMMENT 'Business call session ID',
    channel_uuid        VARCHAR(64)     NULL COMMENT 'Related channel UUID',
    related_channel_uuid VARCHAR(64)    NULL COMMENT 'Related peer channel UUID',
    event_type          VARCHAR(32)     NOT NULL COMMENT 'Business event type',
    from_target         VARCHAR(128)    NULL COMMENT 'Source number or target',
    to_target           VARCHAR(128)    NULL COMMENT 'Destination number or target',
    occurred_at         DATETIME        NOT NULL COMMENT 'Event occurred time',
    metadata_json       TEXT            NULL COMMENT 'Extension metadata JSON',
    create_dept         BIGINT          NULL,
    create_by           BIGINT          NULL,
    create_time         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by           BIGINT          NULL,
    update_time         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_cc_call_event_session (tenant_id, session_id, occurred_at),
    KEY idx_cc_call_event_channel (tenant_id, channel_uuid)
) ENGINE=InnoDB COMMENT='Business call timeline event';

INSERT INTO cc_call_session (
    id, tenant_id, business_call_id, node_id, direction, caller_number, called_number,
    agent_id, agent_extension, call_status, started_at, ringing_at, answered_at, ended_at,
    duration_seconds, billable_seconds, hangup_cause, create_time, update_time, version
)
SELECT
    MIN(id),
    tenant_id,
    COALESCE(NULLIF(call_uuid, ''), channel_uuid),
    MIN(node_id),
    CASE
        WHEN SUM(direction = 'INBOUND') > 0 THEN 'INBOUND'
        WHEN SUM(direction = 'OUTBOUND') > 0 THEN 'OUTBOUND'
        WHEN SUM(direction = 'INTERNAL') > 0 THEN 'INTERNAL'
        ELSE 'UNKNOWN'
    END,
    SUBSTRING_INDEX(GROUP_CONCAT(caller_number ORDER BY started_at ASC SEPARATOR ','), ',', 1),
    SUBSTRING_INDEX(GROUP_CONCAT(called_number ORDER BY started_at ASC SEPARATOR ','), ',', 1),
    MAX(agent_id),
    MAX(agent_extension),
    CASE
        WHEN SUM(call_status <> 'ENDED') = 0 THEN 'ENDED'
        WHEN SUM(call_status = 'BRIDGED') > 0 THEN 'BRIDGED'
        WHEN SUM(call_status = 'ANSWERED') > 0 THEN 'ANSWERED'
        WHEN SUM(call_status = 'RINGING') > 0 THEN 'RINGING'
        ELSE 'CREATED'
    END,
    MIN(started_at),
    MIN(ringing_at),
    MIN(answered_at),
    MAX(ended_at),
    COALESCE(TIMESTAMPDIFF(SECOND, MIN(started_at), MAX(ended_at)), 0),
    COALESCE(TIMESTAMPDIFF(SECOND, MIN(answered_at), MAX(ended_at)), 0),
    SUBSTRING_INDEX(GROUP_CONCAT(hangup_cause ORDER BY ended_at DESC SEPARATOR ','), ',', 1),
    MIN(create_time),
    MAX(update_time),
    0
FROM cc_call_record
GROUP BY tenant_id, COALESCE(NULLIF(call_uuid, ''), channel_uuid);

UPDATE cc_call_record record
JOIN cc_call_session session
  ON session.tenant_id = record.tenant_id
 AND session.business_call_id = COALESCE(NULLIF(record.call_uuid, ''), record.channel_uuid)
SET record.session_id = session.id;
