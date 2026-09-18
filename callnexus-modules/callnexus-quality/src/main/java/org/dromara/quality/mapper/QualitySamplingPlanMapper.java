package org.dromara.quality.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.quality.domain.QualitySamplingPlan;
import org.dromara.quality.domain.response.QualityOptionResponse;
import org.dromara.quality.domain.response.QualitySamplingCandidate;

import java.time.LocalDateTime;
import java.util.List;

public interface QualitySamplingPlanMapper extends BaseMapperPlus<QualitySamplingPlan, QualitySamplingPlan> {

    @Select("""
        <script>
        SELECT session.id AS callSessionId, session.business_call_id AS businessCallId,
               session.direction, session.agent_id AS agentId,
               COALESCE(NULLIF(user.nick_name, ''), NULLIF(user.user_name, ''), NULLIF(agent.agent_name, ''), session.agent_extension) AS agentName,
               session.agent_extension AS agentExtension,
               session.handling_queue_id AS queueId, session.handling_queue_name AS queueName,
               queue.skill_group_id AS skillGroupId, session.started_at AS startedAt, session.ended_at AS endedAt,
               session.duration_seconds AS durationSeconds, session.billable_seconds AS billableSeconds,
               satisfaction.score AS satisfactionScore,
               CASE WHEN session.recording_oss_id IS NOT NULL OR session.recording_media_id IS NOT NULL THEN 1 ELSE 0 END AS hasRecording,
               CASE WHEN transcript.id IS NOT NULL AND transcript.status = 'SUCCESS' THEN 1 ELSE 0 END AS hasTranscript,
               (CASE WHEN satisfaction.score BETWEEN 1 AND 2 THEN 50 ELSE 0 END
                + CASE WHEN COALESCE(session.billable_seconds, 0) BETWEEN 1 AND 15 THEN 20 ELSE 0 END
                + CASE WHEN COALESCE(session.billable_seconds, 0) &gt;= 1800 THEN 15 ELSE 0 END
                + CASE WHEN session.hangup_cause NOT IN ('NORMAL_CLEARING', 'ORIGINATOR_CANCEL') THEN 10 ELSE 0 END
                + CASE WHEN transcript.id IS NOT NULL AND transcript.status = 'SUCCESS' THEN 5 ELSE 0 END) AS riskScore
        FROM cc_call_session session
        LEFT JOIN cc_agent agent ON agent.tenant_id = session.tenant_id AND agent.id = session.agent_id AND agent.deleted = 0
        LEFT JOIN sys_user user ON user.tenant_id = agent.tenant_id AND user.user_id = agent.user_id AND user.del_flag = '0'
        LEFT JOIN cc_call_queue queue ON queue.tenant_id = session.tenant_id AND queue.id = session.handling_queue_id AND queue.deleted = 0
        LEFT JOIN cc_call_satisfaction satisfaction ON satisfaction.tenant_id = session.tenant_id AND satisfaction.session_id = session.id
        LEFT JOIN cc_ai_call_transcript transcript ON transcript.tenant_id = session.tenant_id AND transcript.call_session_id = session.id AND transcript.deleted = 0
        WHERE session.tenant_id = #{tenantId}
          AND session.call_status = 'ENDED'
          AND session.ended_at &gt;= #{windowStart} AND session.ended_at &lt;= #{windowEnd}
          AND (#{plan.directionScope} = 'ALL' OR session.direction = #{plan.directionScope})
          <if test="plan.queueId != null">AND session.handling_queue_id = #{plan.queueId}</if>
          <if test="plan.skillGroupId != null">AND queue.skill_group_id = #{plan.skillGroupId}</if>
          <if test="plan.agentId != null">AND session.agent_id = #{plan.agentId}</if>
          <if test="plan.minDurationSeconds != null">AND COALESCE(session.billable_seconds, 0) &gt;= #{plan.minDurationSeconds}</if>
          <if test="plan.maxDurationSeconds != null">AND COALESCE(session.billable_seconds, 0) &lt;= #{plan.maxDurationSeconds}</if>
          <if test="plan.requireRecording != null and plan.requireRecording">AND (session.recording_oss_id IS NOT NULL OR session.recording_media_id IS NOT NULL)</if>
          <if test="plan.requireRecording != null and !plan.requireRecording">AND session.recording_oss_id IS NULL AND session.recording_media_id IS NULL</if>
          <if test="plan.requireTranscript != null and plan.requireTranscript">AND transcript.id IS NOT NULL AND transcript.status = 'SUCCESS'</if>
          <if test="plan.requireTranscript != null and !plan.requireTranscript">AND (transcript.id IS NULL OR transcript.status != 'SUCCESS')</if>
          <if test="plan.excludeSampled != null and plan.excludeSampled">
            AND NOT EXISTS (SELECT 1 FROM cc_quality_task sampled WHERE sampled.tenant_id = session.tenant_id AND sampled.call_session_id = session.id AND sampled.deleted = 0)
          </if>
          <if test="(plan.excludeSampled == null or !plan.excludeSampled) and plan.cooldownDays != null and plan.cooldownDays &gt; 0">
            AND NOT EXISTS (SELECT 1 FROM cc_quality_task recent WHERE recent.tenant_id = session.tenant_id AND recent.call_session_id = session.id
                AND recent.deleted = 0 AND recent.create_time &gt;= TIMESTAMPADD(DAY, -#{plan.cooldownDays}, #{windowEnd}))
          </if>
        ORDER BY session.ended_at DESC
        LIMIT 20000
        </script>
        """)
    List<QualitySamplingCandidate> selectCandidates(@Param("tenantId") String tenantId,
                                                     @Param("plan") QualitySamplingPlan plan,
                                                     @Param("windowStart") LocalDateTime windowStart,
                                                     @Param("windowEnd") LocalDateTime windowEnd);

    @Select("""
        SELECT id, queue_name AS name FROM cc_call_queue
        WHERE tenant_id = #{tenantId} AND deleted = 0 AND enabled = 1 ORDER BY queue_name
        """)
    List<QualityOptionResponse> selectQueueOptions(@Param("tenantId") String tenantId);

    @Select("""
        SELECT id, group_name AS name FROM cc_skill_group
        WHERE tenant_id = #{tenantId} AND deleted = 0 AND enabled = 1 ORDER BY group_name
        """)
    List<QualityOptionResponse> selectSkillGroupOptions(@Param("tenantId") String tenantId);

    @Select("""
        SELECT agent.id, COALESCE(NULLIF(user.nick_name, ''), NULLIF(user.user_name, ''), NULLIF(agent.agent_name, ''), agent.agent_code) AS name
        FROM cc_agent agent LEFT JOIN sys_user user ON user.tenant_id = agent.tenant_id AND user.user_id = agent.user_id AND user.del_flag = '0'
        WHERE agent.tenant_id = #{tenantId} AND agent.deleted = 0 AND agent.enabled = 1 ORDER BY name
        """)
    List<QualityOptionResponse> selectAgentOptions(@Param("tenantId") String tenantId);
}
