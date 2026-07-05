package org.dromara.ai.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.dromara.ai.domain.AiAgentKnowledgeBase;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface AiAgentKnowledgeBaseMapper extends BaseMapper<AiAgentKnowledgeBase> {
    @Delete("DELETE FROM cc_ai_agent_knowledge_base WHERE tenant_id = #{tenantId} AND agent_id = #{agentId}")
    int deletePhysicallyByAgentId(@Param("tenantId") String tenantId, @Param("agentId") Long agentId);
}
