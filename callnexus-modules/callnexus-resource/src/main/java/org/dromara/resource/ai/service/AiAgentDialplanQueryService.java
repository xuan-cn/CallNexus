package org.dromara.resource.ai.service;

/**
 * AI 语音入口动态拨号计划查询。
 */
public interface AiAgentDialplanQueryService {

    String renderDirectAgent(String tenantId, Long agentId, Long nodeId, String destinationNumber,
                             String context, String sipDomain);
}
