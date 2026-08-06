package org.dromara.agent.service;

import org.dromara.agent.domain.AgentPresenceStatus;
import org.dromara.agent.domain.response.CurrentAgentResponse;

/**
 * 坐席会话状态的统一应用入口。
 *
 * <p>调用方必须显式传入坐席 ID，避免第三方接口错误依赖后台登录用户。</p>
 */
public interface AgentSessionApplicationService {
    CurrentAgentResponse get(Long agentId);

    CurrentAgentResponse signIn(Long agentId);

    CurrentAgentResponse changeStatus(Long agentId, AgentPresenceStatus status);

    void signOut(Long agentId);
}
