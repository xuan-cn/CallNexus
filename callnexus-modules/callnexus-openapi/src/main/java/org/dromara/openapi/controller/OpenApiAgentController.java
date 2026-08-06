package org.dromara.openapi.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.agent.domain.AgentPresenceStatus;
import org.dromara.agent.domain.request.AgentPageQuery;
import org.dromara.agent.domain.response.AgentResponse;
import org.dromara.agent.domain.response.CurrentAgentResponse;
import org.dromara.agent.service.AgentApplicationService;
import org.dromara.agent.service.AgentSessionApplicationService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.openapi.domain.request.OpenApiAgentStatusRequest;
import org.dromara.openapi.domain.response.OpenApiAgentResponse;
import org.dromara.openapi.domain.response.OpenApiPageResponse;
import org.dromara.openapi.security.OpenApiContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/openapi/v1/agents")
@RequiredArgsConstructor
public class OpenApiAgentController {
    private static final int MAX_PAGE_SIZE = 100;

    private final AgentApplicationService agentService;
    private final AgentSessionApplicationService sessionService;

    @GetMapping
    public OpenApiPageResponse<OpenApiAgentResponse> page(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
        @RequestParam(name = "agent_code", required = false) String agentCode,
        @RequestParam(name = "agent_name", required = false) String agentName,
        @RequestParam(required = false) Boolean enabled) {
        OpenApiContext.requireScope("agent.read");
        int normalizedPage = Math.max(1, page);
        int normalizedPageSize = Math.max(1, Math.min(MAX_PAGE_SIZE, pageSize));
        AgentPageQuery query = new AgentPageQuery();
        query.setAgentCode(agentCode);
        query.setAgentName(agentName);
        query.setEnabled(enabled);
        TableDataInfo<AgentResponse> result = agentService.page(query, new PageQuery(normalizedPageSize, normalizedPage));
        List<OpenApiAgentResponse> items = result.getRows().stream().map(this::withSession).toList();
        return new OpenApiPageResponse<>(items, normalizedPage, normalizedPageSize, result.getTotal());
    }

    @GetMapping("/{agentId}")
    public OpenApiAgentResponse get(@PathVariable Long agentId) {
        OpenApiContext.requireScope("agent.read");
        return withSession(agentService.get(agentId));
    }

    @PostMapping("/{agentId}/signin")
    public OpenApiAgentResponse signIn(@PathVariable Long agentId) {
        OpenApiContext.requireScope("agent.signin");
        CurrentAgentResponse session = sessionService.signIn(agentId);
        return OpenApiAgentResponse.from(agentService.get(agentId), session);
    }

    @PostMapping("/{agentId}/signout")
    public OpenApiAgentResponse signOut(@PathVariable Long agentId) {
        OpenApiContext.requireScope("agent.signout");
        sessionService.signOut(agentId);
        return withSession(agentService.get(agentId));
    }

    @PutMapping("/{agentId}/status")
    public OpenApiAgentResponse changeStatus(@PathVariable Long agentId,
                                              @Valid @RequestBody OpenApiAgentStatusRequest request) {
        OpenApiContext.requireScope("agent.status.write");
        if (request.status() != AgentPresenceStatus.IDLE && request.status() != AgentPresenceStatus.BUSY) {
            throw new ServiceException("OpenAPI 仅允许将坐席设置为 IDLE 或 BUSY");
        }
        CurrentAgentResponse session = sessionService.changeStatus(agentId, request.status());
        return OpenApiAgentResponse.from(agentService.get(agentId), session);
    }

    private OpenApiAgentResponse withSession(AgentResponse agent) {
        return OpenApiAgentResponse.from(agent, sessionService.get(agent.getId()));
    }
}
