package org.dromara.agent.service;

import org.dromara.agent.domain.AgentPresenceStatus;
import org.dromara.agent.domain.response.CurrentAgentResponse;
import org.dromara.agent.domain.response.CurrentAgentWebRtcConfigResponse;

public interface CurrentAgentSessionService {
    CurrentAgentResponse current();
    CurrentAgentWebRtcConfigResponse webRtcConfig();
    CurrentAgentResponse signIn();
    CurrentAgentResponse changeStatus(AgentPresenceStatus status);
    void signOut();
}
