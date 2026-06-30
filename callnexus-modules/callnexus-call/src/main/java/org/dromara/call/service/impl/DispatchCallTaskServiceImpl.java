package org.dromara.call.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.call.constant.EslEventNames;
import org.dromara.call.constant.EslHeaders;
import org.dromara.call.domain.CallLeg;
import org.dromara.call.domain.DispatchCallTarget;
import org.dromara.call.domain.DispatchCallTask;
import org.dromara.call.domain.EslEndpoint;
import org.dromara.call.domain.TelephonyEvent;
import org.dromara.call.domain.response.DispatchCallTargetResponse;
import org.dromara.call.domain.response.DispatchCallTaskResponse;
import org.dromara.call.domain.response.DispatchOperatorExtensionResponse;
import org.dromara.call.mapper.CallLegMapper;
import org.dromara.call.mapper.DispatchCallTargetMapper;
import org.dromara.call.mapper.DispatchCallTaskMapper;
import org.dromara.call.service.DispatchCallTaskService;
import org.dromara.call.service.DispatchOperatorExtensionService;
import org.dromara.call.service.TelephonyCommandGateway;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.resource.node.domain.response.FreeSwitchNodeConnectionResponse;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.dromara.resource.sip.domain.response.SipAccountRealtimeResponse;
import org.dromara.resource.sip.service.SipAccountQueryService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class DispatchCallTaskServiceImpl implements DispatchCallTaskService {
    private static final Set<String> UNANSWERED_STATES = Set.of("PENDING", "SUBMITTED", "RINGING");
    private static final Set<String> TERMINAL_STATES = Set.of("FAILED", "CANCELLED", "ENDED");

    private final DispatchCallTaskMapper taskMapper;
    private final DispatchCallTargetMapper targetMapper;
    private final CallLegMapper callLegMapper;
    private final DispatchOperatorExtensionService operatorExtensionService;
    private final SipAccountQueryService sipAccountQueryService;
    private final FreeSwitchNodeQueryService nodeQueryService;
    private final TelephonyCommandGateway commandGateway;

    @Override
    public DispatchCallTaskResponse startSingleCall(String targetExtension) {
        return start("SINGLE", List.of(targetExtension));
    }

    @Override
    public DispatchCallTaskResponse startGroupCall(List<String> targetExtensions) {
        return start("GROUP", targetExtensions);
    }

    private DispatchCallTaskResponse start(String taskType, List<String> requestedExtensions) {
        DispatchOperatorExtensionResponse operator = operatorExtensionService.requireCurrent();
        EslEndpoint endpoint = endpoint(operator.getNodeId());
        Set<String> registeredExtensions = commandGateway.listRegisteredExtensions(endpoint);
        if (!registeredExtensions.contains(operator.getExtension())) {
            throw new ServiceException("当前调度分机未在 FreeSWITCH 注册");
        }
        if (isExtensionBusy(operator.getNodeId(), operator.getExtension())) {
            throw new ServiceException("当前调度分机已有活动通话，不能发起新的调度呼叫");
        }

        List<String> extensions = normalizeTargets(requestedExtensions, operator.getExtension());
        List<SipAccountRealtimeResponse> accounts = validateTargets(operator.getNodeId(), extensions, registeredExtensions);

        DispatchCallTask task = new DispatchCallTask();
        task.setBusinessCallId(UUID.randomUUID().toString());
        task.setConferenceName("dispatch_" + task.getBusinessCallId().replace("-", ""));
        task.setNodeId(operator.getNodeId());
        task.setOperatorUserId(LoginHelper.getUserId());
        task.setOperatorSipAccountId(operator.getSipAccountId());
        task.setOperatorExtension(operator.getExtension());
        task.setOperatorLegUuid(UUID.randomUUID().toString());
        task.setTaskType(taskType);
        task.setTaskState("STARTING");
        task.setTotalCount(accounts.size());
        task.setAnsweredCount(0);
        task.setFailedCount(0);
        task.setCancelledCount(0);
        task.setStartedAt(LocalDateTime.now());
        taskMapper.insert(task);

        List<DispatchCallTarget> targets = new ArrayList<>();
        for (SipAccountRealtimeResponse account : accounts) {
            DispatchCallTarget target = new DispatchCallTarget();
            target.setTaskId(task.getId());
            target.setNodeId(task.getNodeId());
            target.setSipAccountId(account.getSipAccountId());
            target.setTargetExtension(account.getExtension());
            target.setTargetLegUuid(UUID.randomUUID().toString());
            target.setTargetState("PENDING");
            target.setAnswered(false);
            targetMapper.insert(target);
            targets.add(target);
        }

        try {
            commandGateway.originateDispatchParticipant(endpoint, task.getBusinessCallId(), task.getOperatorLegUuid(),
                task.getConferenceName(), task.getOperatorExtension(), task.getOperatorExtension(),
                "DISPATCH_CALL_OPERATOR", task.getId(), null);
        } catch (Exception exception) {
            task.setTaskState("FAILED");
            task.setEndedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            failPendingTargets(targets, "调度分机呼叫失败：" + exception.getMessage());
            throw exception instanceof RuntimeException runtimeException
                ? runtimeException
                : new ServiceException("调度分机呼叫失败：" + exception.getMessage());
        }

        int submittedCount = 0;
        for (DispatchCallTarget target : targets) {
            target.setTargetState("SUBMITTED");
            target.setSubmittedAt(LocalDateTime.now());
            targetMapper.updateById(target);
            try {
                commandGateway.originateDispatchParticipant(endpoint, task.getBusinessCallId(), target.getTargetLegUuid(),
                    task.getConferenceName(), target.getTargetExtension(), task.getOperatorExtension(),
                    "DISPATCH_CALL_TARGET", task.getId(), target.getId());
                submittedCount++;
            } catch (Exception exception) {
                target.setTargetState("FAILED");
                target.setFailureReason(exception.getMessage());
                target.setEndedAt(LocalDateTime.now());
                log.warn("调度呼叫目标提交失败，taskId={}，businessCallId={}，targetId={}，targetExtension={}，targetLegUuid={}，error={}",
                    task.getId(), task.getBusinessCallId(), target.getId(), target.getTargetExtension(),
                    target.getTargetLegUuid(), exception.getMessage());
            }
            targetMapper.updateById(target);
        }
        task.setTaskState(submittedCount == 0 ? "FAILED" : submittedCount == targets.size() ? "RUNNING" : "PARTIAL");
        if (submittedCount == 0) {
            task.setEndedAt(LocalDateTime.now());
            try {
                if (commandGateway.callExists(endpoint, task.getOperatorLegUuid())) {
                    commandGateway.hangup(endpoint, task.getOperatorLegUuid());
                }
            } catch (Exception exception) {
                log.warn("全部调度目标提交失败后，清理调度分机电话腿失败，taskId={}，operatorLegUuid={}，error={}",
                    task.getId(), task.getOperatorLegUuid(), exception.getMessage());
            }
        }
        taskMapper.updateById(task);
        recalculateTask(task.getId());
        log.info("调度{}任务已提交，taskId={}，businessCallId={}，nodeId={}，operatorExtension={}，operatorLegUuid={}，conferenceName={}，targetCount={}，submittedCount={}",
            "GROUP".equals(taskType) ? "组呼" : "单呼", task.getId(), task.getBusinessCallId(), task.getNodeId(),
            task.getOperatorExtension(), task.getOperatorLegUuid(), task.getConferenceName(), targets.size(), submittedCount);
        return get(task.getId());
    }

    @Override
    public List<DispatchCallTaskResponse> listRecent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return taskMapper.selectList(new LambdaQueryWrapper<DispatchCallTask>()
                .orderByDesc(DispatchCallTask::getCreateTime)
                .last("limit " + safeLimit))
            .stream()
            .map(task -> toResponse(task, false))
            .toList();
    }

    @Override
    public DispatchCallTaskResponse get(Long taskId) {
        DispatchCallTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new ServiceException("调度呼叫任务不存在");
        }
        return toResponse(task, true);
    }

    @Override
    public void stopUnanswered(Long taskId) {
        DispatchCallTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new ServiceException("调度呼叫任务不存在");
        }
        EslEndpoint endpoint = endpoint(task.getNodeId());
        List<DispatchCallTarget> targets = targets(taskId);
        int cancelled = 0;
        for (DispatchCallTarget target : targets) {
            if (!UNANSWERED_STATES.contains(target.getTargetState())) {
                continue;
            }
            try {
                if (commandGateway.callExists(endpoint, target.getTargetLegUuid())) {
                    commandGateway.hangup(endpoint, target.getTargetLegUuid());
                }
            } catch (Exception exception) {
                log.warn("停止未接听调度目标时 FreeSWITCH 命令失败，继续更新任务状态，taskId={}，targetId={}，targetLegUuid={}，error={}",
                    taskId, target.getId(), target.getTargetLegUuid(), exception.getMessage());
            }
            target.setTargetState("CANCELLED");
            target.setFailureReason("调度员停止未接听目标");
            target.setEndedAt(LocalDateTime.now());
            targetMapper.updateById(target);
            cancelled++;
        }
        recalculateTask(taskId);
        if (cancelled == 0) {
            throw new ServiceException("当前没有可停止的未接听目标");
        }
        log.info("调度呼叫未接听目标已停止，taskId={}，businessCallId={}，cancelledCount={}",
            taskId, task.getBusinessCallId(), cancelled);
    }

    @Override
    public boolean terminateByOperatorLeg(String operatorLegUuid) {
        if (operatorLegUuid == null || operatorLegUuid.isBlank()) {
            return false;
        }
        DispatchCallTask task = taskMapper.selectOne(new LambdaQueryWrapper<DispatchCallTask>()
            .eq(DispatchCallTask::getOperatorLegUuid, operatorLegUuid)
            .in(DispatchCallTask::getTaskState, "STARTING", "RUNNING", "PARTIAL")
            .last("limit 1"));
        if (task == null) {
            return false;
        }
        commandGateway.terminateConference(endpoint(task.getNodeId()), task.getConferenceName());
        log.info("调度员通过通话控制结束调度会议，taskId={}，businessCallId={}，operatorLegUuid={}，conferenceName={}",
            task.getId(), task.getBusinessCallId(), operatorLegUuid, task.getConferenceName());
        return true;
    }

    @Override
    public void handleEvent(TelephonyEvent event) {
        Long taskId = parseLong(event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_DISPATCH_TASK_ID));
        if (taskId == null) {
            return;
        }
        Long targetId = parseLong(event.headers().get(EslHeaders.VARIABLE_CALLNEXUS_DISPATCH_TARGET_ID));
        if (targetId == null) {
            handleOperatorEvent(taskId, event);
            return;
        }
        DispatchCallTarget target = targetMapper.selectById(targetId);
        if (target == null || !taskId.equals(target.getTaskId())
            || !event.uuid().equals(target.getTargetLegUuid())) {
            log.warn("忽略无法匹配调度目标的电话事件，taskId={}，targetId={}，eventUuid={}，eventName={}",
                taskId, targetId, event.uuid(), event.eventName());
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        switch (event.eventName()) {
            case EslEventNames.CHANNEL_CREATE -> target.setTargetState("SUBMITTED");
            case EslEventNames.CHANNEL_PROGRESS, EslEventNames.CHANNEL_PROGRESS_MEDIA -> {
                target.setTargetState("RINGING");
                if (target.getRingingAt() == null) {
                    target.setRingingAt(now);
                }
            }
            case EslEventNames.CHANNEL_ANSWER, EslEventNames.CHANNEL_BRIDGE -> {
                target.setTargetState("ANSWERED");
                target.setAnswered(true);
                if (target.getAnsweredAt() == null) {
                    target.setAnsweredAt(now);
                }
                target.setFailureReason(null);
            }
            case EslEventNames.CHANNEL_HANGUP, EslEventNames.CHANNEL_HANGUP_COMPLETE, EslEventNames.CHANNEL_DESTROY -> {
                if (!"CANCELLED".equals(target.getTargetState())) {
                    target.setTargetState(Boolean.TRUE.equals(target.getAnswered()) ? "ENDED" : "FAILED");
                    if (!Boolean.TRUE.equals(target.getAnswered())) {
                        target.setFailureReason(hangupReason(event));
                    }
                }
                if (target.getEndedAt() == null) {
                    target.setEndedAt(now);
                }
            }
            default -> {
                return;
            }
        }
        targetMapper.updateById(target);
        recalculateTask(taskId);
    }

    private void handleOperatorEvent(Long taskId, TelephonyEvent event) {
        DispatchCallTask task = taskMapper.selectById(taskId);
        if (task == null || !event.uuid().equals(task.getOperatorLegUuid())) {
            return;
        }
        if (EslEventNames.CHANNEL_HANGUP.equals(event.eventName())) {
            terminateTargetsAfterOperatorHangup(task);
            return;
        }
        if (EslEventNames.CHANNEL_ANSWER.equals(event.eventName())
            || EslEventNames.CHANNEL_BRIDGE.equals(event.eventName())) {
            if ("STARTING".equals(task.getTaskState())) {
                task.setTaskState("RUNNING");
                taskMapper.updateById(task);
            }
        }
    }

    private void terminateTargetsAfterOperatorHangup(DispatchCallTask task) {
        EslEndpoint endpoint = endpoint(task.getNodeId());
        try {
            commandGateway.terminateConference(endpoint, task.getConferenceName());
        } catch (Exception exception) {
            log.debug("调度员挂机时会议已结束或全员挂断命令未执行，继续逐腿清理，taskId={}，conferenceName={}，error={}",
                task.getId(), task.getConferenceName(), exception.getMessage());
        }
        int terminatedCount = 0;
        for (DispatchCallTarget target : targets(task.getId())) {
            if (TERMINAL_STATES.contains(target.getTargetState())) {
                continue;
            }
            try {
                if (commandGateway.callExists(endpoint, target.getTargetLegUuid())) {
                    commandGateway.hangup(endpoint, target.getTargetLegUuid());
                }
            } catch (Exception exception) {
                log.debug("调度员挂机后清理目标电话腿发生并发结束，按幂等结果继续，taskId={}，targetId={}，targetLegUuid={}，error={}",
                    task.getId(), target.getId(), target.getTargetLegUuid(), exception.getMessage());
            }
            if (!Boolean.TRUE.equals(target.getAnswered())) {
                target.setTargetState("CANCELLED");
                target.setFailureReason("调度员已结束调度呼叫");
                target.setEndedAt(LocalDateTime.now());
                targetMapper.updateById(target);
            }
            terminatedCount++;
        }
        recalculateTask(task.getId());
        log.info("调度员已退出调度呼叫，剩余目标电话腿已提交清理，taskId={}，businessCallId={}，operatorLegUuid={}，targetCount={}",
            task.getId(), task.getBusinessCallId(), task.getOperatorLegUuid(), terminatedCount);
    }

    private List<String> normalizeTargets(List<String> requestedExtensions, String operatorExtension) {
        if (requestedExtensions == null || requestedExtensions.isEmpty()) {
            throw new ServiceException("调度呼叫目标不能为空");
        }
        LinkedHashSet<String> extensions = new LinkedHashSet<>();
        for (String value : requestedExtensions) {
            if (value == null || !value.matches("^[0-9*#+]{2,32}$")) {
                throw new ServiceException("目标分机格式不正确");
            }
            if (operatorExtension.equals(value)) {
                throw new ServiceException("调度分机不能呼叫自己");
            }
            extensions.add(value);
        }
        if (extensions.size() > 50) {
            throw new ServiceException("单次组呼最多选择50个分机");
        }
        return List.copyOf(extensions);
    }

    private List<SipAccountRealtimeResponse> validateTargets(Long nodeId, List<String> extensions,
                                                              Set<String> registeredExtensions) {
        List<SipAccountRealtimeResponse> accounts = new ArrayList<>();
        for (String extension : extensions) {
            SipAccountRealtimeResponse account = sipAccountQueryService.findEnabledByNodeAndExtension(nodeId, extension);
            if (account == null) {
                throw new ServiceException("目标分机不存在、已停用或不属于当前 FreeSWITCH 节点：" + extension);
            }
            if (!registeredExtensions.contains(extension)) {
                throw new ServiceException("目标分机未注册，无法发起调度呼叫：" + extension);
            }
            if (isExtensionBusy(nodeId, extension)) {
                throw new ServiceException("目标分机当前正在通话，无法发起调度呼叫：" + extension);
            }
            accounts.add(account);
        }
        return accounts;
    }

    private boolean isExtensionBusy(Long nodeId, String extension) {
        return callLegMapper.exists(new LambdaQueryWrapper<CallLeg>()
            .eq(CallLeg::getNodeId, nodeId)
            .eq(CallLeg::getEndpointExtension, extension)
            .eq(CallLeg::getActive, true)
            .isNull(CallLeg::getEndedAt));
    }

    private void failPendingTargets(List<DispatchCallTarget> targets, String reason) {
        for (DispatchCallTarget target : targets) {
            target.setTargetState("FAILED");
            target.setFailureReason(reason);
            target.setEndedAt(LocalDateTime.now());
            targetMapper.updateById(target);
        }
    }

    private void recalculateTask(Long taskId) {
        DispatchCallTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        List<DispatchCallTarget> targets = targets(taskId);
        int answered = (int) targets.stream().filter(item -> Boolean.TRUE.equals(item.getAnswered())).count();
        int failed = (int) targets.stream().filter(item -> "FAILED".equals(item.getTargetState())).count();
        int cancelled = (int) targets.stream().filter(item -> "CANCELLED".equals(item.getTargetState())).count();
        boolean allTerminal = !targets.isEmpty() && targets.stream().allMatch(item -> TERMINAL_STATES.contains(item.getTargetState()));
        task.setAnsweredCount(answered);
        task.setFailedCount(failed);
        task.setCancelledCount(cancelled);
        if (allTerminal) {
            if (answered == task.getTotalCount()) {
                task.setTaskState("SUCCESS");
            } else if (answered > 0) {
                task.setTaskState("PARTIAL");
            } else if (cancelled == task.getTotalCount()) {
                task.setTaskState("CANCELLED");
            } else {
                task.setTaskState("FAILED");
            }
            if (task.getEndedAt() == null) {
                task.setEndedAt(LocalDateTime.now());
            }
        } else if (answered > 0 || targets.stream().anyMatch(item -> UNANSWERED_STATES.contains(item.getTargetState()))) {
            task.setTaskState("RUNNING");
            task.setEndedAt(null);
        }
        taskMapper.updateById(task);
    }

    private List<DispatchCallTarget> targets(Long taskId) {
        return targetMapper.selectList(new LambdaQueryWrapper<DispatchCallTarget>()
            .eq(DispatchCallTarget::getTaskId, taskId)
            .orderByAsc(DispatchCallTarget::getId));
    }

    private DispatchCallTaskResponse toResponse(DispatchCallTask task, boolean includeTargets) {
        DispatchCallTaskResponse response = new DispatchCallTaskResponse();
        response.setId(task.getId());
        response.setBusinessCallId(task.getBusinessCallId());
        response.setNodeId(task.getNodeId());
        response.setOperatorExtension(task.getOperatorExtension());
        response.setOperatorLegUuid(task.getOperatorLegUuid());
        response.setTaskType(task.getTaskType());
        response.setTaskState(task.getTaskState());
        response.setTotalCount(task.getTotalCount());
        response.setAnsweredCount(task.getAnsweredCount());
        response.setFailedCount(task.getFailedCount());
        response.setCancelledCount(task.getCancelledCount());
        response.setStartedAt(task.getStartedAt());
        response.setEndedAt(task.getEndedAt());
        if (includeTargets) {
            response.setTargets(targets(task.getId()).stream().map(this::toTargetResponse).toList());
        }
        return response;
    }

    private DispatchCallTargetResponse toTargetResponse(DispatchCallTarget target) {
        DispatchCallTargetResponse response = new DispatchCallTargetResponse();
        response.setId(target.getId());
        response.setSipAccountId(target.getSipAccountId());
        response.setTargetExtension(target.getTargetExtension());
        response.setTargetLegUuid(target.getTargetLegUuid());
        response.setTargetState(target.getTargetState());
        response.setAnswered(target.getAnswered());
        response.setFailureReason(target.getFailureReason());
        response.setSubmittedAt(target.getSubmittedAt());
        response.setRingingAt(target.getRingingAt());
        response.setAnsweredAt(target.getAnsweredAt());
        response.setEndedAt(target.getEndedAt());
        return response;
    }

    private String hangupReason(TelephonyEvent event) {
        String cause = event.hangupCause();
        return cause == null || cause.isBlank() ? "目标未接听或呼叫已结束" : cause;
    }

    private Long parseLong(String value) {
        if (value == null || !value.matches("^\\d+$")) {
            return null;
        }
        return Long.valueOf(value);
    }

    private EslEndpoint endpoint(Long nodeId) {
        FreeSwitchNodeConnectionResponse node = nodeQueryService.getEnabledConnection(nodeId);
        return new EslEndpoint(node.getEslHost(), node.getEslPort(), node.getEslPassword(), node.getSipDomain());
    }
}
