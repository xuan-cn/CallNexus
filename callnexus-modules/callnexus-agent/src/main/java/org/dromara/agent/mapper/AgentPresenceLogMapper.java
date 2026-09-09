package org.dromara.agent.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.agent.domain.AgentPresenceLog;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.time.LocalDateTime;
import java.util.List;

public interface AgentPresenceLogMapper extends BaseMapperPlus<AgentPresenceLog, AgentPresenceLog> {

    @Select("SELECT id FROM cc_agent WHERE tenant_id = #{tenantId} AND id = #{agentId} FOR UPDATE")
    Long lockAgent(@Param("tenantId") String tenantId, @Param("agentId") Long agentId);

    @Select("""
        SELECT id, tenant_id, agent_id, previous_status, status, source, business_call_id,
               started_at, ended_at, duration_seconds,
               create_dept, create_by, create_time, update_by, update_time
        FROM cc_agent_presence_log
        WHERE tenant_id = #{tenantId} AND agent_id = #{agentId} AND ended_at IS NULL
        ORDER BY started_at DESC, id DESC LIMIT 1 FOR UPDATE
        """)
    AgentPresenceLog selectOpen(@Param("tenantId") String tenantId, @Param("agentId") Long agentId);

    @Update("""
        UPDATE cc_agent_presence_log
        SET ended_at = #{endedAt},
            duration_seconds = GREATEST(0, TIMESTAMPDIFF(SECOND, started_at, #{endedAt})),
            update_time = #{endedAt}
        WHERE tenant_id = #{tenantId} AND agent_id = #{agentId} AND ended_at IS NULL
        """)
    int closeOpen(@Param("tenantId") String tenantId,
                  @Param("agentId") Long agentId,
                  @Param("endedAt") LocalDateTime endedAt);

    @Select("""
        <script>
        SELECT DISTINCT agent_id
        FROM cc_agent_presence_log
        WHERE tenant_id = #{tenantId} AND ended_at IS NULL AND status != 'OFFLINE'
          AND agent_id IN
        <foreach collection="agentIds" item="agentId" open="(" separator="," close=")">#{agentId}</foreach>
        </script>
        """)
    List<Long> selectOpenNonOfflineAgentIds(@Param("tenantId") String tenantId,
                                            @Param("agentIds") List<Long> agentIds);
}
