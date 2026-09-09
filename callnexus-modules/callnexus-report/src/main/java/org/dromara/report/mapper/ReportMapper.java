package org.dromara.report.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.report.domain.response.AgentReportResponse;
import org.dromara.report.domain.response.CallDetailResponse;
import org.dromara.report.domain.response.CallDistributionResponse;
import org.dromara.report.domain.response.OverviewKpiResponse;
import org.dromara.report.domain.response.QueueReportResponse;
import org.dromara.report.domain.response.ReportTrendPointResponse;

import java.time.LocalDateTime;
import java.util.List;

public interface ReportMapper {

    @Select("""
        SELECT COUNT(*) AS totalCalls,
               SUM(direction = 'INBOUND') AS inboundCalls,
               SUM(direction = 'OUTBOUND') AS outboundCalls,
               SUM(answered_at IS NOT NULL) AS answeredCalls,
               SUM(answered_at IS NULL) AS unansweredCalls,
               COALESCE(ROUND(SUM(answered_at IS NOT NULL) * 100.0 / NULLIF(COUNT(*), 0), 2), 0) AS answerRate,
               COALESCE(SUM(CASE WHEN answered_at IS NOT NULL THEN COALESCE(NULLIF(billable_seconds, 0),
                    GREATEST(0, TIMESTAMPDIFF(SECOND, answered_at, COALESCE(ended_at, #{nowAt})))) ELSE 0 END), 0) AS totalTalkSeconds,
               COALESCE(ROUND(AVG(CASE WHEN answered_at IS NOT NULL THEN COALESCE(NULLIF(billable_seconds, 0),
                    GREATEST(0, TIMESTAMPDIFF(SECOND, answered_at, COALESCE(ended_at, #{nowAt})))) END)), 0) AS averageTalkSeconds,
               (SELECT COUNT(DISTINCT agent_id) FROM cc_agent_presence_log WHERE tenant_id = #{tenantId}
                    AND ended_at IS NULL AND status != 'OFFLINE') AS onlineAgents,
               (SELECT COUNT(DISTINCT qin.session_id) FROM cc_call_event qin
                    JOIN cc_call_session qs ON qs.tenant_id = qin.tenant_id AND qs.id = qin.session_id
                    LEFT JOIN cc_call_event answer ON answer.tenant_id = qin.tenant_id AND answer.session_id = qin.session_id
                        AND answer.event_type = 'AGENT_ANSWER'
                    WHERE qin.tenant_id = #{tenantId} AND qin.event_type = 'QUEUE_IN'
                      AND answer.id IS NULL AND qs.call_status != 'ENDED') AS waitingCalls
        FROM cc_call_session
        WHERE tenant_id = #{tenantId} AND started_at >= #{startAt} AND started_at < #{endAt}
        """)
    OverviewKpiResponse selectOverview(@Param("tenantId") String tenantId,
                                        @Param("startAt") LocalDateTime startAt,
                                        @Param("endAt") LocalDateTime endAt,
                                        @Param("nowAt") LocalDateTime nowAt);

    @Select("""
        SELECT CASE WHEN #{granularity} = 'HOUR' THEN DATE_FORMAT(started_at, '%Y-%m-%d %H:00')
                    ELSE DATE_FORMAT(started_at, '%Y-%m-%d') END AS bucket,
               COUNT(*) AS totalCalls,
               SUM(direction = 'INBOUND') AS inboundCalls,
               SUM(direction = 'OUTBOUND') AS outboundCalls,
               SUM(answered_at IS NOT NULL) AS answeredCalls
        FROM cc_call_session
        WHERE tenant_id = #{tenantId} AND started_at >= #{startAt} AND started_at < #{endAt}
        GROUP BY CASE WHEN #{granularity} = 'HOUR' THEN DATE_FORMAT(started_at, '%Y-%m-%d %H:00')
                      ELSE DATE_FORMAT(started_at, '%Y-%m-%d') END
        ORDER BY bucket
        """)
    List<ReportTrendPointResponse> selectTrend(@Param("tenantId") String tenantId,
                                                @Param("startAt") LocalDateTime startAt,
                                                @Param("endAt") LocalDateTime endAt,
                                                @Param("granularity") String granularity);

    @Select("""
        <script>
        SELECT CASE
                 WHEN answered_at IS NOT NULL THEN 'ANSWERED'
                 WHEN hangup_cause IN ('USER_BUSY') THEN 'BUSY'
                 WHEN hangup_cause IN ('CALL_REJECTED') THEN 'REJECTED'
                 WHEN hangup_cause IN ('NO_ANSWER', 'NO_USER_RESPONSE', 'ORIGINATOR_CANCEL') THEN 'NO_ANSWER'
                 ELSE 'FAILED'
               END AS category,
               COUNT(*) AS count
        FROM cc_call_session
        WHERE tenant_id = #{tenantId} AND started_at &gt;= #{startAt} AND started_at &lt; #{endAt}
        <if test="direction != null and direction != ''">AND direction = #{direction}</if>
        GROUP BY category ORDER BY count DESC
        </script>
        """)
    List<CallDistributionResponse> selectDistribution(@Param("tenantId") String tenantId,
                                                       @Param("startAt") LocalDateTime startAt,
                                                       @Param("endAt") LocalDateTime endAt,
                                                       @Param("direction") String direction);

    @Select("""
        <script>
        SELECT s.id, s.business_call_id AS businessCallId, s.direction,
               CASE WHEN s.direction = 'INBOUND' THEN s.caller_number ELSE s.called_number END AS customerNumber,
               a.agent_name AS agentName, COALESCE(s.owner_agent_extension, s.agent_extension) AS agentExtension,
               s.handling_queue_name AS queueName, s.call_status AS callStatus,
               s.started_at AS startedAt, s.answered_at AS answeredAt, s.ended_at AS endedAt,
               CASE WHEN s.answered_at IS NULL THEN 0 ELSE GREATEST(0, TIMESTAMPDIFF(SECOND, s.started_at, s.answered_at)) END AS waitSeconds,
               CASE WHEN s.answered_at IS NULL THEN 0 ELSE COALESCE(NULLIF(s.billable_seconds, 0),
                    GREATEST(0, TIMESTAMPDIFF(SECOND, s.answered_at, COALESCE(s.ended_at, #{nowAt})))) END AS talkSeconds,
               s.hangup_cause AS hangupCause
        FROM cc_call_session s
        LEFT JOIN cc_agent a ON a.tenant_id = s.tenant_id AND a.id = COALESCE(s.owner_agent_id, s.agent_id) AND a.deleted = 0
        WHERE s.tenant_id = #{tenantId} AND s.started_at &gt;= #{startAt} AND s.started_at &lt; #{endAt}
        <if test="direction != null and direction != ''">AND s.direction = #{direction}</if>
        <if test="answerResult == 'ANSWERED'">AND s.answered_at IS NOT NULL</if>
        <if test="answerResult == 'UNANSWERED'">AND s.answered_at IS NULL</if>
        <if test="agentId != null">AND COALESCE(s.owner_agent_id, s.agent_id) = #{agentId}</if>
        <if test="queueId != null">AND s.handling_queue_id = #{queueId}</if>
        <if test="keyword != null and keyword != ''">
          AND (s.caller_number LIKE CONCAT('%', #{keyword}, '%') OR s.called_number LIKE CONCAT('%', #{keyword}, '%')
               OR a.agent_name LIKE CONCAT('%', #{keyword}, '%') OR s.handling_queue_name LIKE CONCAT('%', #{keyword}, '%'))
        </if>
        ORDER BY s.started_at DESC, s.id DESC
        </script>
        """)
    Page<CallDetailResponse> selectCallDetails(Page<CallDetailResponse> page,
                                                @Param("tenantId") String tenantId,
                                                @Param("startAt") LocalDateTime startAt,
                                                @Param("endAt") LocalDateTime endAt,
                                                @Param("nowAt") LocalDateTime nowAt,
                                                @Param("direction") String direction,
                                                @Param("answerResult") String answerResult,
                                                @Param("agentId") Long agentId,
                                                @Param("queueId") Long queueId,
                                                @Param("keyword") String keyword);

    @Select("""
        <script>
        SELECT a.id AS agentId, a.agent_code AS agentCode, a.agent_name AS agentName,
               sip.extension,
               GROUP_CONCAT(DISTINCT sg.group_name ORDER BY sg.group_name SEPARATOR '、') AS skillGroupNames,
               COALESCE(cs.handled_count, 0) AS handledCount,
               COALESCE(cs.inbound_answered_count, 0) AS inboundAnsweredCount,
               COALESCE(cs.outbound_answered_count, 0) AS outboundAnsweredCount,
               COALESCE(cs.missed_count, 0) AS missedCount,
               COALESCE(cs.total_talk_seconds, 0) AS totalTalkSeconds,
               COALESCE(cs.average_talk_seconds, 0) AS averageTalkSeconds,
               COALESCE(cs.average_response_seconds, 0) AS averageResponseSeconds,
               COALESCE(ps.online_seconds, 0) AS onlineSeconds,
               COALESCE(ps.not_ready_seconds, 0) AS notReadySeconds,
               COALESCE(ps.after_call_seconds, 0) AS afterCallSeconds,
               COALESCE(ROUND(cs.total_talk_seconds * 100.0 / NULLIF(ps.online_seconds, 0), 2), 0) AS utilizationRate
        FROM cc_agent a
        LEFT JOIN cc_agent_extension ext ON ext.tenant_id = a.tenant_id AND ext.agent_id = a.id AND ext.deleted = 0
        LEFT JOIN cc_sip_account sip ON sip.tenant_id = a.tenant_id AND sip.id = ext.sip_account_id AND sip.deleted = 0
        LEFT JOIN cc_skill_group_member member ON member.tenant_id = a.tenant_id AND member.agent_id = a.id AND member.deleted = 0
        LEFT JOIN cc_skill_group sg ON sg.tenant_id = a.tenant_id AND sg.id = member.skill_group_id AND sg.deleted = 0
        LEFT JOIN (
          SELECT leg.agent_id,
                 COUNT(DISTINCT CASE WHEN leg.answered_at IS NOT NULL THEN leg.leg_uuid END) AS handled_count,
                 COUNT(DISTINCT CASE WHEN leg.answered_at IS NOT NULL AND session.direction = 'INBOUND' THEN leg.leg_uuid END) AS inbound_answered_count,
                 COUNT(DISTINCT CASE WHEN leg.answered_at IS NOT NULL AND session.direction = 'OUTBOUND' THEN leg.leg_uuid END) AS outbound_answered_count,
                 COUNT(DISTINCT CASE WHEN leg.ringing_at IS NOT NULL AND leg.answered_at IS NULL AND leg.active = 0
                    AND COALESCE(leg.hangup_cause, '') NOT IN ('LOSE_RACE','ORIGINATOR_CANCEL') THEN leg.leg_uuid END) AS missed_count,
                 COALESCE(SUM(CASE WHEN leg.answered_at IS NOT NULL THEN GREATEST(0, TIMESTAMPDIFF(SECOND, leg.answered_at, COALESCE(leg.ended_at, #{nowAt}))) ELSE 0 END), 0) AS total_talk_seconds,
                 COALESCE(ROUND(AVG(CASE WHEN leg.answered_at IS NOT NULL THEN GREATEST(0, TIMESTAMPDIFF(SECOND, leg.answered_at, COALESCE(leg.ended_at, #{nowAt}))) END)), 0) AS average_talk_seconds,
                 COALESCE(ROUND(AVG(CASE WHEN leg.answered_at IS NOT NULL AND leg.ringing_at IS NOT NULL THEN GREATEST(0, TIMESTAMPDIFF(SECOND, leg.ringing_at, leg.answered_at)) END)), 0) AS average_response_seconds
          FROM cc_call_leg leg
          LEFT JOIN cc_call_session session ON session.tenant_id = leg.tenant_id AND session.id = leg.session_id
          WHERE leg.tenant_id = #{tenantId} AND COALESCE(leg.ringing_at, leg.answered_at, leg.create_time) &gt;= #{startAt}
            AND COALESCE(leg.ringing_at, leg.answered_at, leg.create_time) &lt; #{endAt} AND leg.agent_id IS NOT NULL
          GROUP BY leg.agent_id
        ) cs ON cs.agent_id = a.id
        LEFT JOIN (
          SELECT agent_id,
                 SUM(CASE WHEN status != 'OFFLINE' THEN GREATEST(0, TIMESTAMPDIFF(SECOND, GREATEST(started_at, #{startAt}), LEAST(COALESCE(ended_at, #{nowAt}), #{endAt}))) ELSE 0 END) AS online_seconds,
                 SUM(CASE WHEN status = 'NOT_READY' THEN GREATEST(0, TIMESTAMPDIFF(SECOND, GREATEST(started_at, #{startAt}), LEAST(COALESCE(ended_at, #{nowAt}), #{endAt}))) ELSE 0 END) AS not_ready_seconds,
                 SUM(CASE WHEN status = 'AFTER_CALL' THEN GREATEST(0, TIMESTAMPDIFF(SECOND, GREATEST(started_at, #{startAt}), LEAST(COALESCE(ended_at, #{nowAt}), #{endAt}))) ELSE 0 END) AS after_call_seconds
          FROM cc_agent_presence_log
          WHERE tenant_id = #{tenantId} AND started_at &lt; #{endAt} AND COALESCE(ended_at, #{nowAt}) &gt; #{startAt}
          GROUP BY agent_id
        ) ps ON ps.agent_id = a.id
        WHERE a.tenant_id = #{tenantId} AND a.deleted = 0
        <if test="agentId != null">AND a.id = #{agentId}</if>
        <if test="skillGroupId != null">AND EXISTS (SELECT 1 FROM cc_skill_group_member selected_member WHERE selected_member.tenant_id = a.tenant_id AND selected_member.agent_id = a.id AND selected_member.skill_group_id = #{skillGroupId} AND selected_member.deleted = 0)</if>
        <if test="keyword != null and keyword != ''">AND (a.agent_code LIKE CONCAT('%', #{keyword}, '%') OR a.agent_name LIKE CONCAT('%', #{keyword}, '%') OR sip.extension LIKE CONCAT('%', #{keyword}, '%'))</if>
        GROUP BY a.id, a.agent_code, a.agent_name, sip.extension, cs.handled_count, cs.inbound_answered_count,
                 cs.outbound_answered_count, cs.missed_count, cs.total_talk_seconds, cs.average_talk_seconds,
                 cs.average_response_seconds, ps.online_seconds, ps.not_ready_seconds, ps.after_call_seconds
        ORDER BY handledCount DESC, a.agent_code ASC
        </script>
        """)
    List<AgentReportResponse> selectAgents(@Param("tenantId") String tenantId,
                                           @Param("startAt") LocalDateTime startAt,
                                           @Param("endAt") LocalDateTime endAt,
                                           @Param("nowAt") LocalDateTime nowAt,
                                           @Param("agentId") Long agentId,
                                           @Param("skillGroupId") Long skillGroupId,
                                           @Param("keyword") String keyword);

    @Select("""
        <script>
        SELECT q.id AS queueId, q.queue_code AS queueCode, q.queue_name AS queueName, sg.group_name AS skillGroupName,
               COUNT(DISTINCT qin.id) AS enteredCount,
               COUNT(DISTINCT CASE WHEN answer.id IS NOT NULL THEN qin.id END) AS answeredCount,
               COUNT(DISTINCT CASE WHEN abandon.id IS NOT NULL THEN qin.id END) AS abandonedCount,
               COUNT(DISTINCT CASE WHEN timeout_event.id IS NOT NULL THEN qin.id END) AS timeoutCount,
               COALESCE(ROUND(COUNT(DISTINCT CASE WHEN answer.id IS NOT NULL THEN qin.id END) * 100.0 / NULLIF(COUNT(DISTINCT qin.id), 0), 2), 0) AS answerRate,
               COALESCE(ROUND(COUNT(DISTINCT CASE WHEN abandon.id IS NOT NULL THEN qin.id END) * 100.0 / NULLIF(COUNT(DISTINCT qin.id), 0), 2), 0) AS abandonRate,
               COALESCE(ROUND(AVG(CASE WHEN answer.id IS NOT NULL THEN GREATEST(0, TIMESTAMPDIFF(SECOND, qin.occurred_at, answer.occurred_at)) END)), 0) AS averageWaitSeconds,
               COALESCE(MAX(CASE WHEN answer.id IS NOT NULL THEN GREATEST(0, TIMESTAMPDIFF(SECOND, qin.occurred_at, answer.occurred_at)) END), 0) AS maximumWaitSeconds,
               COALESCE(ROUND(COUNT(DISTINCT CASE WHEN answer.id IS NOT NULL AND TIMESTAMPDIFF(SECOND, qin.occurred_at, answer.occurred_at) &lt;= 20 THEN qin.id END) * 100.0 / NULLIF(COUNT(DISTINCT qin.id), 0), 2), 0) AS serviceLevel
        FROM cc_call_queue q
        LEFT JOIN cc_skill_group sg ON sg.tenant_id = q.tenant_id AND sg.id = q.skill_group_id AND sg.deleted = 0
        LEFT JOIN cc_call_event qin ON qin.tenant_id = q.tenant_id AND qin.event_type = 'QUEUE_IN'
             AND CAST(JSON_UNQUOTE(JSON_EXTRACT(qin.metadata_json, '$.queueId')) AS UNSIGNED) = q.id
             AND qin.occurred_at &gt;= #{startAt} AND qin.occurred_at &lt; #{endAt}
        LEFT JOIN cc_call_event answer ON answer.tenant_id = qin.tenant_id AND answer.session_id = qin.session_id
             AND answer.event_type = 'AGENT_ANSWER' AND answer.occurred_at &gt;= qin.occurred_at
        LEFT JOIN cc_call_event abandon ON abandon.tenant_id = qin.tenant_id AND abandon.session_id = qin.session_id
             AND abandon.event_type = 'ABANDON' AND abandon.occurred_at &gt;= qin.occurred_at
        LEFT JOIN cc_call_event timeout_event ON timeout_event.tenant_id = qin.tenant_id AND timeout_event.session_id = qin.session_id
             AND timeout_event.event_type = 'QUEUE_TIMEOUT' AND timeout_event.occurred_at &gt;= qin.occurred_at
        WHERE q.tenant_id = #{tenantId} AND q.deleted = 0
        <if test="queueId != null">AND q.id = #{queueId}</if>
        <if test="skillGroupId != null">AND q.skill_group_id = #{skillGroupId}</if>
        <if test="keyword != null and keyword != ''">AND (q.queue_code LIKE CONCAT('%', #{keyword}, '%') OR q.queue_name LIKE CONCAT('%', #{keyword}, '%'))</if>
        GROUP BY q.id, q.queue_code, q.queue_name, sg.group_name
        ORDER BY enteredCount DESC, q.queue_code ASC
        </script>
        """)
    List<QueueReportResponse> selectQueues(@Param("tenantId") String tenantId,
                                           @Param("startAt") LocalDateTime startAt,
                                           @Param("endAt") LocalDateTime endAt,
                                           @Param("queueId") Long queueId,
                                           @Param("skillGroupId") Long skillGroupId,
                                           @Param("keyword") String keyword);
}
