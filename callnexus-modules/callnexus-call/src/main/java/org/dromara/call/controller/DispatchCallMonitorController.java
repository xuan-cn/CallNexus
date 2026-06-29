package org.dromara.call.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.call.domain.response.DispatchActiveCallResponse;
import org.dromara.call.domain.response.DispatchCallTopologyResponse;
import org.dromara.call.service.DispatchCallMonitorService;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/dispatch/calls")
@RequiredArgsConstructor
public class DispatchCallMonitorController {
    private final DispatchCallMonitorService monitorService;

    @GetMapping("/active")
    @SaCheckPermission("callcenter:dispatch-monitor:list")
    public R<List<DispatchActiveCallResponse>> listActiveCalls() {
        return R.ok(monitorService.listActiveCalls());
    }

    @GetMapping("/{businessCallId}/topology")
    @SaCheckPermission("callcenter:dispatch-monitor:query")
    public R<DispatchCallTopologyResponse> getTopology(@PathVariable String businessCallId) {
        return R.ok(monitorService.getTopology(businessCallId));
    }
}
