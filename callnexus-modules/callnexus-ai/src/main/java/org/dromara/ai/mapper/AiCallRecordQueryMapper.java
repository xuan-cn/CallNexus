package org.dromara.ai.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.ai.domain.request.AiCallRecordQuery;
import org.dromara.ai.domain.response.AiCallRecordResponse;

public interface AiCallRecordQueryMapper {

    @Select("""
        <script>
        SELECT
            t.id AS transcriptId,
            t.call_session_id AS callSessionId,
            t.business_call_id AS businessCallId,
            t.status AS transcriptStatus,
            t.failure_reason AS transcriptFailureReason,
            t.started_at AS transcriptStartedAt,
            t.finished_at AS transcriptFinishedAt,
            s.node_id AS nodeId,
            s.direction AS direction,
            s.caller_number AS callerNumber,
            s.called_number AS calledNumber,
            s.agent_id AS agentId,
            s.agent_extension AS agentExtension,
            s.owner_agent_id AS ownerAgentId,
            s.owner_agent_extension AS ownerAgentExtension,
            s.handling_queue_id AS handlingQueueId,
            s.handling_queue_name AS handlingQueueName,
            s.call_status AS callStatus,
            s.started_at AS startedAt,
            s.answered_at AS answeredAt,
            s.ended_at AS endedAt,
            s.duration_seconds AS durationSeconds,
            s.billable_seconds AS billableSeconds,
            s.hangup_cause AS hangupCause,
            s.recording_oss_id AS recordingOssId,
            s.recording_media_id AS recordingMediaId,
            s.recording_file_name AS recordingFileName,
            s.recording_status AS recordingStatus,
            stats.segment_count AS segmentCount,
            stats.customer_segment_count AS customerSegmentCount,
            stats.ai_segment_count AS aiSegmentCount,
            stats.agent_segment_count AS agentSegmentCount
        FROM cc_ai_call_transcript t
        LEFT JOIN cc_call_session s
            ON s.tenant_id = t.tenant_id
            AND s.id = t.call_session_id
        LEFT JOIN (
            SELECT
                transcript_id,
                COUNT(1) AS segment_count,
                SUM(CASE WHEN speaker = 'CUSTOMER' THEN 1 ELSE 0 END) AS customer_segment_count,
                SUM(CASE WHEN speaker = 'AI' THEN 1 ELSE 0 END) AS ai_segment_count,
                SUM(CASE WHEN speaker = 'AGENT' THEN 1 ELSE 0 END) AS agent_segment_count
            FROM cc_ai_call_transcript_segment
            WHERE tenant_id = #{tenantId}
                AND deleted = 0
            GROUP BY transcript_id
        ) stats ON stats.transcript_id = t.id
        WHERE t.tenant_id = #{tenantId}
            AND t.deleted = 0
            AND t.id = (
                SELECT MAX(t2.id)
                FROM cc_ai_call_transcript t2
                WHERE t2.tenant_id = t.tenant_id
                    AND t2.call_session_id = t.call_session_id
                    AND t2.deleted = 0
            )
            AND EXISTS (
                SELECT 1
                FROM cc_ai_call_transcript_segment seg
                WHERE seg.tenant_id = t.tenant_id
                    AND seg.transcript_id = t.id
                    AND seg.speaker = 'AI'
                    AND seg.deleted = 0
            )
            <if test="query.participantNumber != null and query.participantNumber != ''">
                AND (
                    s.caller_number LIKE CONCAT('%', #{query.participantNumber}, '%')
                    OR s.called_number LIKE CONCAT('%', #{query.participantNumber}, '%')
                )
            </if>
            <if test="query.callerNumber != null and query.callerNumber != ''">
                AND s.caller_number LIKE CONCAT('%', #{query.callerNumber}, '%')
            </if>
            <if test="query.calledNumber != null and query.calledNumber != ''">
                AND s.called_number LIKE CONCAT('%', #{query.calledNumber}, '%')
            </if>
            <if test="query.agentExtension != null and query.agentExtension != ''">
                AND (
                    s.agent_extension = #{query.agentExtension}
                    OR s.owner_agent_extension = #{query.agentExtension}
                )
            </if>
            <if test="query.callStatus != null and query.callStatus != ''">
                AND s.call_status = #{query.callStatus}
            </if>
        ORDER BY COALESCE(s.started_at, t.create_time) DESC, t.id DESC
        </script>
        """)
    Page<AiCallRecordResponse> page(Page<AiCallRecordResponse> page,
                                    @Param("tenantId") String tenantId,
                                    @Param("query") AiCallRecordQuery query);
}
