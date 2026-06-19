package org.dromara.agent.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.agent.domain.response.CallQueueAgentStatusResponse;
import org.dromara.agent.domain.response.CallQueueMonitorOverviewResponse;
import org.dromara.agent.domain.response.CallQueueMonitorResponse;
import org.dromara.agent.domain.response.CallQueueRecentCallResponse;
import org.dromara.agent.domain.response.CallQueueRecentEventResponse;
import org.dromara.agent.domain.response.CallQueueTrendPointResponse;
import org.dromara.agent.service.CallQueueMonitorService;
import org.dromara.common.core.domain.R;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/call-queues")
@RequiredArgsConstructor
public class CallQueueMonitorController {
    private final CallQueueMonitorService service;

    @GetMapping("/monitor")
    @SaCheckPermission("callcenter:queue-monitor:list")
    public R<List<CallQueueMonitorResponse>> list() {
        return R.ok(service.list());
    }

    @GetMapping("/monitor/overview")
    @SaCheckPermission("callcenter:queue-monitor:query")
    public R<CallQueueMonitorOverviewResponse> overview() {
        return R.ok(service.overview());
    }

    @GetMapping("/{queueId}/monitor")
    @SaCheckPermission("callcenter:queue-monitor:query")
    public R<CallQueueMonitorResponse> detail(@PathVariable Long queueId) {
        return R.ok(service.detail(queueId));
    }

    @GetMapping("/{queueId}/agents/status")
    @SaCheckPermission("callcenter:queue-monitor:query")
    public R<List<CallQueueAgentStatusResponse>> agents(@PathVariable Long queueId) {
        return R.ok(service.agents(queueId));
    }

    @GetMapping("/{queueId}/statistics/trend")
    @SaCheckPermission("callcenter:queue-monitor:query")
    public R<List<CallQueueTrendPointResponse>> trend(
        @PathVariable Long queueId,
        @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date) {
        return R.ok(service.trend(queueId, date));
    }

    @GetMapping("/{queueId}/events/recent")
    @SaCheckPermission("callcenter:queue-monitor:query")
    public R<List<CallQueueRecentEventResponse>> recentEvents(
        @PathVariable Long queueId,
        @RequestParam(required = false) Integer limit) {
        return R.ok(service.recentEvents(queueId, limit));
    }

    @GetMapping("/{queueId}/calls/recent")
    @SaCheckPermission("callcenter:queue-monitor:query")
    public R<List<CallQueueRecentCallResponse>> recentCalls(
        @PathVariable Long queueId,
        @RequestParam(required = false) Integer limit) {
        return R.ok(service.recentCalls(queueId, limit));
    }
}
