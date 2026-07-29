package org.dromara.ai.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.dromara.ai.domain.AiIntentUtterance;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

public interface AiIntentUtteranceMapper extends BaseMapperPlus<AiIntentUtterance, AiIntentUtterance> {
    @Delete("DELETE FROM cc_ai_intent_utterance WHERE tenant_id = #{tenantId} AND intent_id = #{intentId}")
    int deletePhysicallyByIntentId(@Param("tenantId") String tenantId, @Param("intentId") Long intentId);
}
