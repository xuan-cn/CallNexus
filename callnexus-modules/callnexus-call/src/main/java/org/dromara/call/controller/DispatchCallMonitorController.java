package org.dromara.call.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.call.domain.response.DispatchActiveCallResponse;
import org.dromara.call.domain.response.DispatchCallTopologyResponse;
import org.dromara.call.domain.response.DispatchExtensionStatusResponse;
import org.dromara.call.domain.request.TransferCallRequest;
import org.dromara.call.domain.request.DispatchMonitorRequest;
import org.dromara.call.domain.request.BindDispatchOperatorExtensionRequest;
import org.dromara.call.domain.request.DispatchGroupCallRequest;
import org.dromara.call.domain.request.DispatchSingleCallRequest;
import org.dromara.call.domain.request.DispatchBroadcastRequest;
import org.dromara.call.domain.request.DispatchIntercomRequest;
import org.dromara.call.domain.request.DispatchIntercomTalkRequest;
import org.dromara.call.domain.response.DispatchCallTaskResponse;
import org.dromara.call.domain.response.DispatchOperatorExtensionResponse;
import org.dromara.call.service.DispatchCallControlService;
import org.dromara.call.service.DispatchCallMonitorService;
import org.dromara.call.service.DispatchCallTaskService;
import org.dromara.call.service.DispatchOperatorExtensionService;
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
    private final DispatchCallTaskService taskService;
    private final DispatchOperatorExtensionService operatorExtensionService;

    @GetMapping("/operator-extension")
    @SaCheckPermission("callcenter:dispatch-monitor:list")
    public R<DispatchOperatorExtensionResponse> getOperatorExtension() {
        return R.ok(operatorExtensionService.current());
    }

    @PostMapping("/operator-extension")
    @SaCheckPermission("callcenter:dispatch-control:operator-extension")
    public R<DispatchOperatorExtensionResponse> bindOperatorExtension(
        @Valid @RequestBody BindDispatchOperatorExtensionRequest request) {
        return R.ok(operatorExtensionService.bindCurrent(request.getSipAccountId()));
    }

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

    @PostMapping("/single")
    @SaCheckPermission("callcenter:dispatch-control:call")
    public R<DispatchCallTaskResponse> startSingleCall(@Valid @RequestBody DispatchSingleCallRequest request) {
        return R.ok(taskService.startSingleCall(request.getTargetExtension()));
    }

    @PostMapping("/group")
    @SaCheckPermission("callcenter:dispatch-control:group-call")
    public R<DispatchCallTaskResponse> startGroupCall(@Valid @RequestBody DispatchGroupCallRequest request) {
        return R.ok(taskService.startGroupCall(request.getTargetExtensions()));
    }

    @PostMapping("/broadcast")
    @SaCheckPermission("callcenter:dispatch-control:broadcast")
    public R<DispatchCallTaskResponse> startBroadcast(@Valid @RequestBody DispatchBroadcastRequest request) {
        return R.ok(taskService.startBroadcast(request.getMediaAssetId(), request.getTargetExtensions()));
    }

    @PostMapping("/intercom")
    @SaCheckPermission("callcenter:dispatch-control:intercom")
    public R<DispatchCallTaskResponse> startIntercom(@Valid @RequestBody DispatchIntercomRequest request) {
        return R.ok(taskService.startIntercom(request.getTargetExtension()));
    }

    @GetMapping("/tasks")
    @SaCheckPermission("callcenter:dispatch-call-task:list")
    public R<List<DispatchCallTaskResponse>> listTasks() {
        return R.ok(taskService.listRecent(30));
    }

    @GetMapping("/tasks/{taskId}")
    @SaCheckPermission("callcenter:dispatch-call-task:query")
    public R<DispatchCallTaskResponse> getTask(@PathVariable Long taskId) {
        return R.ok(taskService.get(taskId));
    }

    @PostMapping("/tasks/{taskId}/stop-unanswered")
    @SaCheckPermission("callcenter:dispatch-control:stop-group")
    public R<Void> stopUnanswered(@PathVariable Long taskId) {
        taskService.stopUnanswered(taskId);
        return R.ok();
    }

    @PostMapping("/tasks/{taskId}/terminate-broadcast")
    @SaCheckPermission("callcenter:dispatch-control:stop-broadcast")
    public R<Void> terminateBroadcast(@PathVariable Long taskId) {
        taskService.terminateBroadcast(taskId);
        return R.ok();
    }

    @PostMapping("/tasks/{taskId}/intercom/talk")
    @SaCheckPermission("callcenter:dispatch-control:intercom-talk")
    public R<Void> setIntercomTalking(@PathVariable Long taskId,
                                      @Valid @RequestBody DispatchIntercomTalkRequest request) {
        taskService.setIntercomTalking(taskId, Boolean.TRUE.equals(request.getTalking()));
        return R.ok();
    }

    @PostMapping("/tasks/{taskId}/terminate-intercom")
    @SaCheckPermission("callcenter:dispatch-control:stop-intercom")
    public R<Void> terminateIntercom(@PathVariable Long taskId) {
        taskService.terminateIntercom(taskId);
        return R.ok();
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

    @PostMapping("/{businessCallId}/pickup")
    @SaCheckPermission("callcenter:dispatch-control:pickup")
    public R<String> pickupRingingCall(@PathVariable String businessCallId,
                                       @Valid @RequestBody DispatchMonitorRequest request) {
        return R.ok(controlService.pickupRingingCall(businessCallId, request.getTargetExtension()));
    }
}
