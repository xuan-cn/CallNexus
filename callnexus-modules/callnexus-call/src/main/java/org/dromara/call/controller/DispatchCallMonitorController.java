package org.dromara.call.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.call.domain.response.DispatchActiveCallResponse;
import org.dromara.call.domain.response.DispatchCallTopologyResponse;
import org.dromara.call.domain.response.DispatchExtensionStatusResponse;
import org.dromara.call.domain.request.TransferCallRequest;
import org.dromara.call.domain.request.DispatchMonitorRequest;
import org.dromara.call.service.DispatchCallControlService;
import org.dromara.call.service.DispatchCallMonitorService;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/dispatch/calls")
@RequiredArgsConstructor
public class DispatchCallMonitorController {
    private final DispatchCallMonitorService monitorService;
    private final DispatchCallControlService controlService;

    @GetMapping("/active")
    @SaCheckPermission("callcenter:dispatch-monitor:list")
    public R<List<DispatchActiveCallResponse>> listActiveCalls() {
        return R.ok(monitorService.listActiveCalls());
    }

    @GetMapping("/extensions")
    @SaCheckPermission("callcenter:dispatch-monitor:list")
    public R<List<DispatchExtensionStatusResponse>> listExtensionStatuses() {
        return R.ok(monitorService.listExtensionStatuses());
    }

    @GetMapping("/{businessCallId}/topology")
    @SaCheckPermission("callcenter:dispatch-monitor:query")
    public R<DispatchCallTopologyResponse> getTopology(@PathVariable String businessCallId) {
        return R.ok(monitorService.getTopology(businessCallId));
    }

    @PostMapping("/{businessCallId}/force-hangup")
    @SaCheckPermission("callcenter:dispatch-control:hangup")
    public R<Void> forceHangup(@PathVariable String businessCallId) {
        controlService.forceHangup(businessCallId);
        return R.ok();
    }

    @PostMapping("/{businessCallId}/force-transfer")
    @SaCheckPermission("callcenter:dispatch-control:transfer")
    public R<Void> forceTransfer(@PathVariable String businessCallId,
                                 @Valid @RequestBody TransferCallRequest request) {
        controlService.forceTransferToExtension(businessCallId, request.getTargetExtension());
        return R.ok();
    }

    @PostMapping("/{businessCallId}/monitor")
    @SaCheckPermission("callcenter:dispatch-control:monitor")
    public R<String> startMonitor(@PathVariable String businessCallId,
                                  @Valid @RequestBody DispatchMonitorRequest request) {
        return R.ok(controlService.startMonitor(businessCallId, request.getTargetExtension()));
    }

    @PostMapping("/{businessCallId}/whisper")
    @SaCheckPermission("callcenter:dispatch-control:whisper")
    public R<String> startWhisper(@PathVariable String businessCallId,
                                  @Valid @RequestBody DispatchMonitorRequest request) {
        return R.ok(controlService.startWhisper(businessCallId, request.getTargetExtension()));
    }

    @PostMapping("/{businessCallId}/barge")
    @SaCheckPermission("callcenter:dispatch-control:barge")
    public R<String> startBarge(@PathVariable String businessCallId,
                                @Valid @RequestBody DispatchMonitorRequest request) {
        return R.ok(controlService.startBarge(businessCallId, request.getTargetExtension()));
    }
}
