package org.dromara.agent.service;

import org.dromara.agent.domain.request.*;
import org.dromara.agent.domain.response.AgentResponse;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;

public interface AgentApplicationService {
    TableDataInfo<AgentResponse> page(AgentPageQuery query, PageQuery pageQuery);
    AgentResponse get(Long id);
    Long create(CreateAgentRequest request);
    void update(Long id, UpdateAgentRequest request);
    void delete(Long id);
    void bindExtension(Long agentId, BindAgentExtensionRequest request);
    void unbindExtension(Long agentId);
}
