package org.dromara.agent.service;

import org.dromara.agent.domain.response.AgentRealtimeTargetResponse;

public interface AgentRealtimeQueryService {
    AgentRealtimeTargetResponse findByNodeAndExtension(Long nodeId, String extension);
}
