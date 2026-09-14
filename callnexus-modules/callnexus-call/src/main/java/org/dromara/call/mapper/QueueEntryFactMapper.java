package org.dromara.call.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface QueueEntryFactMapper {

    @Insert("""
        INSERT IGNORE INTO cc_queue_entry_fact (
            id, tenant_id, session_id, business_call_id, queue_in_event_id,
            queue_id, queue_code_snapshot, queue_name_snapshot,
            skill_group_id, skill_group_name_snapshot, node_id,
            customer_number, entry_channel_uuid, entered_at,
            outcome, wait_seconds, create_time, update_time
        )
        SELECT #{eventId}, #{tenantId}, session.id, session.business_call_id, #{eventId},
               #{queueId}, COALESCE(#{queueCode}, queue.queue_code), COALESCE(#{queueName}, queue.queue_name),
               queue.skill_group_id, skill_group.group_name, COALESCE(#{nodeId}, session.node_id),
               CASE WHEN session.direction = 'INBOUND' THEN session.caller_number ELSE session.called_number END,
               #{channelUuid}, #{enteredAt}, 'WAITING', 0, #{enteredAt}, #{enteredAt}
        FROM cc_call_session session
        LEFT JOIN cc_call_queue queue ON queue.tenant_id = session.tenant_id AND queue.id = #{queueId}
        LEFT JOIN cc_skill_group skill_group ON skill_group.tenant_id = queue.tenant_id
             AND skill_group.id = queue.skill_group_id
        WHERE session.tenant_id = #{tenantId} AND session.id = #{sessionId}
        """)
    int insertEntry(@Param("tenantId") String tenantId,
                    @Param("sessionId") Long sessionId,
                    @Param("eventId") Long eventId,
                    @Param("queueId") Long queueId,
                    @Param("queueCode") String queueCode,
                    @Param("queueName") String queueName,
                    @Param("nodeId") Long nodeId,
                    @Param("channelUuid") String channelUuid,
                    @Param("enteredAt") LocalDateTime enteredAt);

    @Update("""
        UPDATE cc_queue_entry_fact fact
        SET fact.answered_at = #{answeredAt}, fact.ended_at = #{answeredAt}, fact.outcome = 'ANSWERED',
            fact.answer_agent_id = #{agentId},
            fact.answer_agent_name_snapshot = (SELECT agent.agent_name FROM cc_agent agent
                WHERE agent.tenant_id = #{tenantId} AND agent.id = #{agentId} LIMIT 1),
            fact.answer_agent_extension = #{agentExtension},
            fact.wait_seconds = GREATEST(0, TIMESTAMPDIFF(SECOND, fact.entered_at, #{answeredAt})),
            fact.update_time = #{answeredAt}
        WHERE fact.tenant_id = #{tenantId} AND fact.session_id = #{sessionId}
          AND fact.outcome = 'WAITING'
          AND (#{queueId} IS NULL OR fact.queue_id = #{queueId})
        ORDER BY fact.entered_at DESC, fact.id DESC
        LIMIT 1
        """)
    int markAnswered(@Param("tenantId") String tenantId,
                     @Param("sessionId") Long sessionId,
                     @Param("queueId") Long queueId,
                     @Param("agentId") Long agentId,
                     @Param("agentExtension") String agentExtension,
                     @Param("answeredAt") LocalDateTime answeredAt);

    @Update("""
        UPDATE cc_queue_entry_fact
        SET ended_at = #{endedAt}, outcome = #{outcome},
            wait_seconds = GREATEST(0, TIMESTAMPDIFF(SECOND, entered_at, #{endedAt})),
            update_time = #{endedAt}
        WHERE tenant_id = #{tenantId} AND session_id = #{sessionId} AND outcome = 'WAITING'
        ORDER BY entered_at DESC, id DESC
        LIMIT 1
        """)
    int markTerminated(@Param("tenantId") String tenantId,
                       @Param("sessionId") Long sessionId,
                       @Param("outcome") String outcome,
                       @Param("endedAt") LocalDateTime endedAt);
}
