package org.dromara.agent.service;

import org.dromara.agent.domain.AgentPresenceStatus;
import org.dromara.agent.domain.response.CurrentAgentResponse;

public interface CurrentAgentSessionService {
    CurrentAgentResponse current();
    CurrentAgentResponse signIn();
    CurrentAgentResponse changeStatus(AgentPresenceStatus status);
    void signOut();
}
