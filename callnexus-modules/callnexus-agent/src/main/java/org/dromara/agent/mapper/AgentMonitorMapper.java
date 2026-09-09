package org.dromara.agent.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.agent.domain.response.AgentMonitorActiveCallResponse;
import org.dromara.agent.domain.response.AgentMonitorCallResponse;
import org.dromara.agent.domain.response.AgentMonitorPresenceDurationResponse;
import org.dromara.agent.domain.response.AgentMonitorPresenceLogResponse;
import org.dromara.agent.domain.response.AgentMonitorResponse;
import org.dromara.agent.domain.response.AgentMonitorSkillGroupResponse;
import org.dromara.agent.domain.response.AgentMonitorStatisticsResponse;

import java.time.LocalDateTime;
import java.util.List;

public interface AgentMonitorMapper {

    @Select("""
        SELECT id, group_name AS groupName
        FROM cc_skill_group
        WHERE tenant_id = #{tenantId} AND deleted = 0 AND enabled = 1
        ORDER BY group_name ASC, id ASC
        """)
    List<AgentMonitorSkillGroupResponse> selectSkillGroups(@Param("tenantId") String tenantId);

    @Select("""
        <script>
        SELECT a.id AS agentId, a.agent_code AS agentCode, a.agent_name AS agentName,
               a.user_id AS userId, sip.extension AS extension, a.enabled AS enabled,
               GROUP_CONCAT(DISTINCT sg.id ORDER BY sg.group_name SEPARATOR ',') AS skillGroupIds,
               GROUP_CONCAT(DISTINCT sg.group_name ORDER BY sg.group_name SEPARATOR '、') AS skillGroupNames
        FROM cc_agent a
        LEFT JOIN cc_agent_extension ext ON ext.tenant_id = a.tenant_id AND ext.agent_id = a.id AND ext.deleted = 0
        LEFT JOIN cc_sip_account sip ON sip.tenant_id = a.tenant_id AND sip.id = ext.sip_account_id AND sip.deleted = 0
        LEFT JOIN cc_skill_group_member member ON member.tenant_id = a.tenant_id AND member.agent_id = a.id AND member.deleted = 0
        LEFT JOIN cc_skill_group sg ON sg.tenant_id = a.tenant_id AND sg.id = member.skill_group_id AND sg.deleted = 0
        WHERE a.tenant_id = #{tenantId} AND a.deleted = 0
        <if test="keyword != null and keyword != ''">
          AND (a.agent_code LIKE CONCAT('%', #{keyword}, '%')
            OR a.agent_name LIKE CONCAT('%', #{keyword}, '%')
            OR sip.extension LIKE CONCAT('%', #{keyword}, '%'))
        </if>
        <if test="skillGroupId != null">
          AND EXISTS (SELECT 1 FROM cc_skill_group_member selected_member
                      WHERE selected_member.tenant_id = a.tenant_id
                        AND selected_member.agent_id = a.id
                        AND selected_member.skill_group_id = #{skillGroupId}
                        AND selected_member.deleted = 0)
        </if>
        <if test="enabled != null">AND a.enabled = #{enabled}</if>
        GROUP BY a.id, a.agent_code, a.agent_name, a.user_id, sip.extension, a.enabled
        ORDER BY a.agent_code ASC, a.id ASC
        </script>
        """)
    List<AgentMonitorResponse> selectAgents(@Param("tenantId") String tenantId,
                                            @Param("keyword") String keyword,
                                            @Param("skillGroupId") Long skillGroupId,
                                            @Param("enabled") Boolean enabled);

    @Select("""
        <script>
        SELECT leg.agent_id AS agentId,
               COUNT(DISTINCT CASE WHEN leg.answered_at &gt;= #{startAt} AND leg.answered_at &lt; #{endAt} THEN leg.leg_uuid END) AS todayHandledCount,
               COUNT(DISTINCT CASE WHEN leg.answered_at &gt;= #{startAt} AND leg.answered_at &lt; #{endAt} AND session.direction = 'INBOUND' THEN leg.leg_uuid END) AS inboundAnsweredCount,
               COUNT(DISTINCT CASE WHEN leg.answered_at &gt;= #{startAt} AND leg.answered_at &lt; #{endAt} AND session.direction = 'OUTBOUND' THEN leg.leg_uuid END) AS outboundAnsweredCount,
               COUNT(DISTINCT CASE WHEN leg.ringing_at &gt;= #{startAt} AND leg.ringing_at &lt; #{endAt}
                    AND leg.answered_at IS NULL AND leg.active = 0
                    AND COALESCE(leg.hangup_cause, '') NOT IN ('LOSE_RACE', 'ORIGINATOR_CANCEL') THEN leg.leg_uuid END) AS missedOfferCount,
               COALESCE(SUM(CASE WHEN leg.answered_at &gt;= #{startAt} AND leg.answered_at &lt; #{endAt}
                    THEN GREATEST(0, TIMESTAMPDIFF(SECOND, leg.answered_at, COALESCE(leg.ended_at, #{nowAt}))) ELSE 0 END), 0) AS totalTalkSeconds,
               COALESCE(ROUND(AVG(CASE WHEN leg.answered_at &gt;= #{startAt} AND leg.answered_at &lt; #{endAt}
                    THEN GREATEST(0, TIMESTAMPDIFF(SECOND, leg.answered_at, COALESCE(leg.ended_at, #{nowAt}))) END)), 0) AS averageTalkSeconds
        FROM cc_call_leg leg
        LEFT JOIN cc_call_session session ON session.tenant_id = leg.tenant_id AND session.id = leg.session_id
        WHERE leg.tenant_id = #{tenantId} AND leg.agent_id IN
        <foreach collection="agentIds" item="agentId" open="(" separator="," close=")">#{agentId}</foreach>
          AND ((leg.answered_at &gt;= #{startAt} AND leg.answered_at &lt; #{endAt})
            OR (leg.ringing_at &gt;= #{startAt} AND leg.ringing_at &lt; #{endAt}))
        GROUP BY leg.agent_id
        </script>
        """)
    List<AgentMonitorStatisticsResponse> selectStatistics(@Param("tenantId") String tenantId,
                                                          @Param("agentIds") List<Long> agentIds,
                                                          @Param("startAt") LocalDateTime startAt,
                                                          @Param("endAt") LocalDateTime endAt,
                                                          @Param("nowAt") LocalDateTime nowAt);

    @Select("""
        <script>
        SELECT agent_id AS agentId,
               COALESCE(SUM(CASE WHEN status != 'OFFLINE' THEN GREATEST(0, TIMESTAMPDIFF(SECOND,
                    GREATEST(started_at, #{startAt}), LEAST(COALESCE(ended_at, #{nowAt}), #{endAt}))) ELSE 0 END), 0) AS onlineSeconds,
               COALESCE(SUM(CASE WHEN status = 'IDLE' THEN GREATEST(0, TIMESTAMPDIFF(SECOND,
                    GREATEST(started_at, #{startAt}), LEAST(COALESCE(ended_at, #{nowAt}), #{endAt}))) ELSE 0 END), 0) AS idleSeconds,
               COALESCE(SUM(CASE WHEN status = 'NOT_READY' THEN GREATEST(0, TIMESTAMPDIFF(SECOND,
                    GREATEST(started_at, #{startAt}), LEAST(COALESCE(ended_at, #{nowAt}), #{endAt}))) ELSE 0 END), 0) AS notReadySeconds,
               COALESCE(SUM(CASE WHEN status = 'BUSY' THEN GREATEST(0, TIMESTAMPDIFF(SECOND,
                    GREATEST(started_at, #{startAt}), LEAST(COALESCE(ended_at, #{nowAt}), #{endAt}))) ELSE 0 END), 0) AS busySeconds,
               COALESCE(SUM(CASE WHEN status = 'AFTER_CALL' THEN GREATEST(0, TIMESTAMPDIFF(SECOND,
                    GREATEST(started_at, #{startAt}), LEAST(COALESCE(ended_at, #{nowAt}), #{endAt}))) ELSE 0 END), 0) AS afterCallSeconds
        FROM cc_agent_presence_log
        WHERE tenant_id = #{tenantId} AND agent_id IN
        <foreach collection="agentIds" item="agentId" open="(" separator="," close=")">#{agentId}</foreach>
          AND started_at &lt; #{endAt}
          AND COALESCE(ended_at, #{nowAt}) &gt; #{startAt}
        GROUP BY agent_id
        </script>
        """)
    List<AgentMonitorPresenceDurationResponse> selectPresenceDurations(@Param("tenantId") String tenantId,
                                                                       @Param("agentIds") List<Long> agentIds,
                                                                       @Param("startAt") LocalDateTime startAt,
                                                                       @Param("endAt") LocalDateTime endAt,
                                                                       @Param("nowAt") LocalDateTime nowAt);

    @Select("""
        <script>
        SELECT leg.agent_id AS agentId, leg.business_call_id AS businessCallId,
               session.direction AS direction,
               CASE WHEN session.direction = 'INBOUND' THEN session.caller_number ELSE session.called_number END AS peerNumber,
               leg.leg_state AS callState,
               COALESCE(leg.answered_at, leg.ringing_at, leg.create_time) AS startedAt
        FROM cc_call_leg leg
        LEFT JOIN cc_call_session session ON session.tenant_id = leg.tenant_id AND session.id = leg.session_id
        WHERE leg.tenant_id = #{tenantId} AND leg.active = 1 AND leg.agent_id IN
        <foreach collection="agentIds" item="agentId" open="(" separator="," close=")">#{agentId}</foreach>
        ORDER BY COALESCE(leg.answered_at, leg.ringing_at, leg.create_time) DESC
        </script>
        """)
    List<AgentMonitorActiveCallResponse> selectActiveCalls(@Param("tenantId") String tenantId,
                                                           @Param("agentIds") List<Long> agentIds);

    @Select("""
        SELECT leg.id AS legId, leg.session_id AS sessionId, leg.business_call_id AS businessCallId,
               session.direction AS direction,
               CASE WHEN session.direction = 'INBOUND' THEN session.caller_number ELSE session.called_number END AS customerNumber,
               leg.leg_state AS legState, leg.ringing_at AS ringingAt, leg.answered_at AS answeredAt,
               leg.ended_at AS endedAt,
               CASE WHEN leg.answered_at IS NULL THEN 0 ELSE GREATEST(0, TIMESTAMPDIFF(SECOND, leg.answered_at, COALESCE(leg.ended_at, NOW()))) END AS talkSeconds,
               leg.hangup_cause AS hangupCause
        FROM cc_call_leg leg
        LEFT JOIN cc_call_session session ON session.tenant_id = leg.tenant_id AND session.id = leg.session_id
        WHERE leg.tenant_id = #{tenantId} AND leg.agent_id = #{agentId}
        ORDER BY COALESCE(leg.ringing_at, leg.create_time) DESC
        """)
    Page<AgentMonitorCallResponse> selectRecentCalls(Page<AgentMonitorCallResponse> page,
                                                     @Param("tenantId") String tenantId,
                                                     @Param("agentId") Long agentId);

    @Select("""
        SELECT id, previous_status AS previousStatus, status, source,
               business_call_id AS businessCallId, started_at AS startedAt, ended_at AS endedAt,
               CASE WHEN ended_at IS NULL THEN GREATEST(0, TIMESTAMPDIFF(SECOND, started_at, NOW()))
                    ELSE duration_seconds END AS durationSeconds
        FROM cc_agent_presence_log
        WHERE tenant_id = #{tenantId} AND agent_id = #{agentId}
          AND started_at < #{endAt} AND COALESCE(ended_at, NOW()) > #{startAt}
        ORDER BY started_at DESC, id DESC
        """)
    Page<AgentMonitorPresenceLogResponse> selectPresenceLogs(Page<AgentMonitorPresenceLogResponse> page,
                                                             @Param("tenantId") String tenantId,
                                                             @Param("agentId") Long agentId,
                                                             @Param("startAt") LocalDateTime startAt,
                                                             @Param("endAt") LocalDateTime endAt);
}
