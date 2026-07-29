package org.dromara.ai.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.dromara.ai.domain.AiAgentIntent;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

public interface AiAgentIntentMapper extends BaseMapperPlus<AiAgentIntent, AiAgentIntent> {
    @Delete("DELETE FROM cc_ai_agent_intent WHERE tenant_id = #{tenantId} AND intent_id = #{intentId}")
    int deletePhysicallyByIntentId(@Param("tenantId") String tenantId, @Param("intentId") Long intentId);
}
