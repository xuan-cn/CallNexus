package org.dromara.agent.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.agent.domain.response.CallQueueAgentStatusResponse;
import org.dromara.agent.domain.response.CallQueueMonitorResponse;
import org.dromara.agent.domain.response.CallQueueRecentCallResponse;
import org.dromara.agent.domain.response.CallQueueRecentEventResponse;
import org.dromara.agent.domain.response.CallQueueTrendPointResponse;

import java.time.LocalDateTime;
import java.util.List;

public interface CallQueueMonitorMapper {

    @Select("""
        SELECT
            q.id AS queueId,
            q.queue_code AS queueCode,
            q.queue_name AS queueName,
            ng.group_name AS nodeGroupName,
            sg.group_name AS skillGroupName,
            q.sync_status AS syncStatus,
            q.sync_error AS syncError,
            q.last_synced_at AS lastSyncedAt,
            q.enabled AS enabled,
            q.max_wait_seconds AS maxWaitSeconds,
            COALESCE(today.entered_count, 0) AS enteredCount,
            COALESCE(today.answered_count, 0) AS answeredCount,
            COALESCE(today.abandoned_count, 0) AS abandonedCount,
            COALESCE(today.timeout_count, 0) AS timeoutCount,
            COALESCE(waiting.waiting_count, 0) AS waitingCount,
            COALESCE(waiting.ringing_count, 0) AS ringingCount,
            COALESCE(waiting.longest_wait_seconds, 0) AS longestWaitSeconds,
            COALESCE(today.average_wait_seconds, 0) AS averageWaitSeconds,
            COALESCE(agents.total_agent_count, 0) AS totalAgentCount
        FROM cc_call_queue q
        LEFT JOIN cc_freeswitch_node_group ng ON ng.tenant_id = q.tenant_id AND ng.id = q.node_group_id AND ng.deleted = 0
        LEFT JOIN cc_skill_group sg ON sg.tenant_id = q.tenant_id AND sg.id = q.skill_group_id AND sg.deleted = 0
        LEFT JOIN (
            SELECT
                CAST(JSON_UNQUOTE(JSON_EXTRACT(qin.metadata_json, '$.queueId')) AS UNSIGNED) AS queue_id,
                COUNT(DISTINCT qin.session_id) AS entered_count,
                COUNT(DISTINCT CASE WHEN answer.id IS NOT NULL THEN qin.session_id END) AS answered_count,
                COUNT(DISTINCT CASE WHEN abandon.id IS NOT NULL THEN qin.session_id END) AS abandoned_count,
                COUNT(DISTINCT CASE WHEN timeout_event.id IS NOT NULL THEN qin.session_id END) AS timeout_count,
                COALESCE(ROUND(AVG(CASE WHEN answer.id IS NOT NULL THEN TIMESTAMPDIFF(SECOND, qin.occurred_at, answer.occurred_at) END)), 0) AS average_wait_seconds
            FROM cc_call_event qin
            LEFT JOIN cc_call_event answer ON answer.tenant_id = qin.tenant_id AND answer.session_id = qin.session_id AND answer.event_type = 'AGENT_ANSWER'
            LEFT JOIN cc_call_event abandon ON abandon.tenant_id = qin.tenant_id AND abandon.session_id = qin.session_id AND abandon.event_type = 'ABANDON'
            LEFT JOIN cc_call_event timeout_event ON timeout_event.tenant_id = qin.tenant_id AND timeout_event.session_id = qin.session_id AND timeout_event.event_type = 'QUEUE_TIMEOUT'
            WHERE qin.tenant_id = #{tenantId}
              AND qin.event_type = 'QUEUE_IN'
              AND qin.occurred_at >= #{startAt}
              AND qin.occurred_at < #{endAt}
            GROUP BY CAST(JSON_UNQUOTE(JSON_EXTRACT(qin.metadata_json, '$.queueId')) AS UNSIGNED)
        ) today ON today.queue_id = q.id
        LEFT JOIN (
            SELECT
                CAST(JSON_UNQUOTE(JSON_EXTRACT(qin.metadata_json, '$.queueId')) AS UNSIGNED) AS queue_id,
                COUNT(DISTINCT qin.session_id) AS waiting_count,
                COUNT(DISTINCT CASE WHEN ring.id IS NOT NULL THEN qin.session_id END) AS ringing_count,
                COALESCE(MAX(GREATEST(0, TIMESTAMPDIFF(SECOND, qin.occurred_at, #{nowAt}))), 0) AS longest_wait_seconds
            FROM cc_call_event qin
            JOIN cc_call_session session ON session.tenant_id = qin.tenant_id AND session.id = qin.session_id
            LEFT JOIN cc_call_event answer ON answer.tenant_id = qin.tenant_id AND answer.session_id = qin.session_id AND answer.event_type = 'AGENT_ANSWER'
            LEFT JOIN cc_call_event ring ON ring.tenant_id = qin.tenant_id AND ring.session_id = qin.session_id AND ring.event_type = 'AGENT_RING'
            WHERE qin.tenant_id = #{tenantId}
              AND qin.event_type = 'QUEUE_IN'
              AND answer.id IS NULL
              AND session.call_status <> 'ENDED'
            GROUP BY CAST(JSON_UNQUOTE(JSON_EXTRACT(qin.metadata_json, '$.queueId')) AS UNSIGNED)
        ) waiting ON waiting.queue_id = q.id
        LEFT JOIN (
            SELECT skill_group_id, COUNT(DISTINCT agent_id) AS total_agent_count
            FROM cc_skill_group_member
            WHERE tenant_id = #{tenantId} AND deleted = 0
            GROUP BY skill_group_id
        ) agents ON agents.skill_group_id = q.skill_group_id
        WHERE q.tenant_id = #{tenantId}
          AND q.deleted = 0
        ORDER BY q.queue_code ASC
        """)
    List<CallQueueMonitorResponse> selectMonitorList(@Param("tenantId") String tenantId,
                                                     @Param("startAt") LocalDateTime startAt,
                                                     @Param("endAt") LocalDateTime endAt,
                                                     @Param("nowAt") LocalDateTime nowAt);

    @Select("""
        SELECT
            a.id AS agentId,
            a.agent_code AS agentCode,
            a.agent_name AS agentName,
            a.user_id AS userId,
            sip.extension AS extension,
            a.enabled AS enabled,
            last_answer.last_answered_at AS lastAnsweredAt
        FROM cc_call_queue q
        JOIN cc_skill_group_member member ON member.tenant_id = q.tenant_id AND member.skill_group_id = q.skill_group_id AND member.deleted = 0
        JOIN cc_agent a ON a.tenant_id = q.tenant_id AND a.id = member.agent_id AND a.deleted = 0
        LEFT JOIN cc_agent_extension ext ON ext.tenant_id = q.tenant_id AND ext.agent_id = a.id AND ext.deleted = 0
        LEFT JOIN cc_sip_account sip ON sip.tenant_id = q.tenant_id AND sip.id = ext.sip_account_id AND sip.deleted = 0
        LEFT JOIN (
            SELECT
                CAST(JSON_UNQUOTE(JSON_EXTRACT(metadata_json, '$.agentId')) AS UNSIGNED) AS agent_id,
                MAX(occurred_at) AS last_answered_at
            FROM cc_call_event
            WHERE tenant_id = #{tenantId}
              AND event_type = 'AGENT_ANSWER'
            GROUP BY CAST(JSON_UNQUOTE(JSON_EXTRACT(metadata_json, '$.agentId')) AS UNSIGNED)
        ) last_answer ON last_answer.agent_id = a.id
        WHERE q.tenant_id = #{tenantId}
          AND q.id = #{queueId}
          AND q.deleted = 0
        ORDER BY member.priority ASC, member.skill_level DESC, a.agent_code ASC
        """)
    List<CallQueueAgentStatusResponse> selectQueueAgents(@Param("tenantId") String tenantId,
                                                         @Param("queueId") Long queueId);

    @Select("""
        SELECT
            HOUR(qin.occurred_at) AS hour,
            COUNT(DISTINCT qin.session_id) AS enteredCount,
            COUNT(DISTINCT CASE WHEN answer.id IS NOT NULL THEN qin.session_id END) AS answeredCount,
            COUNT(DISTINCT CASE WHEN abandon.id IS NOT NULL THEN qin.session_id END) AS abandonedCount,
            COUNT(DISTINCT CASE WHEN timeout_event.id IS NOT NULL THEN qin.session_id END) AS timeoutCount
        FROM cc_call_event qin
        LEFT JOIN cc_call_event answer ON answer.tenant_id = qin.tenant_id AND answer.session_id = qin.session_id AND answer.event_type = 'AGENT_ANSWER'
        LEFT JOIN cc_call_event abandon ON abandon.tenant_id = qin.tenant_id AND abandon.session_id = qin.session_id AND abandon.event_type = 'ABANDON'
        LEFT JOIN cc_call_event timeout_event ON timeout_event.tenant_id = qin.tenant_id AND timeout_event.session_id = qin.session_id AND timeout_event.event_type = 'QUEUE_TIMEOUT'
        WHERE qin.tenant_id = #{tenantId}
          AND qin.event_type = 'QUEUE_IN'
          AND CAST(JSON_UNQUOTE(JSON_EXTRACT(qin.metadata_json, '$.queueId')) AS UNSIGNED) = #{queueId}
          AND qin.occurred_at >= #{startAt}
          AND qin.occurred_at < #{endAt}
        GROUP BY HOUR(qin.occurred_at)
        ORDER BY HOUR(qin.occurred_at)
        """)
    List<CallQueueTrendPointResponse> selectTrend(@Param("tenantId") String tenantId,
                                                  @Param("queueId") Long queueId,
                                                  @Param("startAt") LocalDateTime startAt,
                                                  @Param("endAt") LocalDateTime endAt);

    @Select("""
        SELECT
            e.id AS eventId,
            e.session_id AS sessionId,
            e.event_type AS eventType,
            s.caller_number AS callerNumber,
            s.called_number AS calledNumber,
            s.agent_extension AS agentExtension,
            CASE
                WHEN e.event_type IN ('ABANDON', 'QUEUE_TIMEOUT', 'AGENT_NO_ANSWER') THEN e.event_type
                ELSE s.hangup_cause
            END AS hangupCause,
            COALESCE(TIMESTAMPDIFF(SECOND, qin.occurred_at, e.occurred_at), 0) AS waitSeconds,
            e.from_target AS fromTarget,
            e.to_target AS toTarget,
            e.occurred_at AS occurredAt,
            e.metadata_json AS metadataJson
        FROM cc_call_event e
        JOIN cc_call_session s ON s.tenant_id = e.tenant_id AND s.id = e.session_id
        LEFT JOIN cc_call_event qin ON qin.tenant_id = e.tenant_id AND qin.session_id = e.session_id AND qin.event_type = 'QUEUE_IN'
        WHERE e.tenant_id = #{tenantId}
          AND (
              CAST(JSON_UNQUOTE(JSON_EXTRACT(e.metadata_json, '$.queueId')) AS UNSIGNED) = #{queueId}
              OR s.handling_queue_id = #{queueId}
          )
        ORDER BY e.occurred_at DESC
        LIMIT #{limit}
        """)
    List<CallQueueRecentEventResponse> selectRecentEvents(@Param("tenantId") String tenantId,
                                                          @Param("queueId") Long queueId,
                                                          @Param("limit") int limit);

    @Select("""
        SELECT
            s.id AS sessionId,
            s.business_call_id AS businessCallId,
            s.direction AS direction,
            s.caller_number AS callerNumber,
            s.called_number AS calledNumber,
            s.agent_id AS agentId,
            s.agent_extension AS agentExtension,
            s.call_status AS callStatus,
            s.started_at AS startedAt,
            s.answered_at AS answeredAt,
            s.ended_at AS endedAt,
            COALESCE(GREATEST(0, TIMESTAMPDIFF(SECOND, qin.occurred_at, COALESCE(answer.occurred_at, timeout_event.occurred_at, abandon.occurred_at, s.ended_at))), 0) AS waitSeconds,
            s.duration_seconds AS durationSeconds,
            s.billable_seconds AS billableSeconds,
            CASE
                WHEN timeout_event.id IS NOT NULL THEN 'QUEUE_TIMEOUT'
                WHEN abandon.id IS NOT NULL THEN 'ABANDON'
                WHEN answer.id IS NULL AND s.call_status = 'ENDED' THEN 'NO_ANSWER'
                ELSE s.hangup_cause
            END AS hangupCause,
            s.recording_status AS recordingStatus
        FROM cc_call_session s
        LEFT JOIN cc_call_event qin ON qin.tenant_id = s.tenant_id AND qin.session_id = s.id AND qin.event_type = 'QUEUE_IN'
        LEFT JOIN cc_call_event answer ON answer.tenant_id = s.tenant_id AND answer.session_id = s.id AND answer.event_type = 'AGENT_ANSWER'
        LEFT JOIN cc_call_event abandon ON abandon.tenant_id = s.tenant_id AND abandon.session_id = s.id AND abandon.event_type = 'ABANDON'
        LEFT JOIN cc_call_event timeout_event ON timeout_event.tenant_id = s.tenant_id AND timeout_event.session_id = s.id AND timeout_event.event_type = 'QUEUE_TIMEOUT'
        WHERE s.tenant_id = #{tenantId}
          AND (
              s.handling_queue_id = #{queueId}
              OR CAST(JSON_UNQUOTE(JSON_EXTRACT(qin.metadata_json, '$.queueId')) AS UNSIGNED) = #{queueId}
          )
        ORDER BY COALESCE(s.started_at, qin.occurred_at) DESC
        LIMIT #{limit}
        """)
    List<CallQueueRecentCallResponse> selectRecentCalls(@Param("tenantId") String tenantId,
                                                        @Param("queueId") Long queueId,
                                                        @Param("limit") int limit);
}
