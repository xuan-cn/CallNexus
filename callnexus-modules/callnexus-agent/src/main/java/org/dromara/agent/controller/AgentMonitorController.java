package org.dromara.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.agent.domain.request.AgentMonitorPageQuery;
import org.dromara.agent.domain.response.AgentMonitorCallResponse;
import org.dromara.agent.domain.response.AgentMonitorOverviewResponse;
import org.dromara.agent.domain.response.AgentMonitorPresenceLogResponse;
import org.dromara.agent.domain.response.AgentMonitorResponse;
import org.dromara.agent.domain.response.AgentMonitorSkillGroupResponse;
import org.dromara.agent.service.AgentMonitorService;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/agent-monitor")
@RequiredArgsConstructor
public class AgentMonitorController {
    private final AgentMonitorService service;

    @GetMapping("/skill-groups")
    @SaCheckPermission("callcenter:agent-monitor:list")
    public R<List<AgentMonitorSkillGroupResponse>> skillGroups() {
        return R.ok(service.skillGroups());
    }

    @GetMapping("/overview")
    @SaCheckPermission("callcenter:agent-monitor:query")
    public R<AgentMonitorOverviewResponse> overview(AgentMonitorPageQuery query) {
        return R.ok(service.overview(query));
    }

    @GetMapping("/agents")
    @SaCheckPermission("callcenter:agent-monitor:list")
    public TableDataInfo<AgentMonitorResponse> page(AgentMonitorPageQuery query, PageQuery pageQuery) {
        return service.page(query, pageQuery);
    }

    @GetMapping("/agents/{agentId}/calls")
    @SaCheckPermission("callcenter:agent-monitor:query")
    public TableDataInfo<AgentMonitorCallResponse> recentCalls(@PathVariable Long agentId, PageQuery pageQuery) {
        return service.recentCalls(agentId, pageQuery);
    }

    @GetMapping("/agents/{agentId}/presence-logs")
    @SaCheckPermission("callcenter:agent-monitor:query")
    public TableDataInfo<AgentMonitorPresenceLogResponse> presenceLogs(@PathVariable Long agentId,
                                                                       AgentMonitorPageQuery query,
                                                                       PageQuery pageQuery) {
        return service.presenceLogs(agentId, query, pageQuery);
    }
}
