package org.dromara.agent.service;

import org.dromara.agent.service.model.AgentAvailability;

public interface AgentAvailabilityQueryService {

    AgentAvailability get(Long agentId);
}

