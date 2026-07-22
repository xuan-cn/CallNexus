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
import org.dromara.resource.media.domain.response.MediaAssetResponse;
import org.dromara.resource.media.domain.response.MediaSyncResponse;
import org.dromara.resource.media.service.MediaAssetApplicationService;
import org.dromara.resource.media.service.MediaPublicationService;
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
    private static final Set<String> TERMINAL_TASK_STATES = Set.of("SUCCESS", "PARTIAL", "FAILED", "CANCELLED");

    private final DispatchCallTaskMapper taskMapper;
    private final DispatchCallTargetMapper targetMapper;
    private final CallLegMapper callLegMapper;
    private final DispatchOperatorExtensionService operatorExtensionService;
    private final SipAccountQueryService sipAccountQueryService;
    private final FreeSwitchNodeQueryService nodeQueryService;
    private final TelephonyCommandGateway commandGateway;
    private final MediaAssetApplicationService mediaAssetService;
    private final MediaPublicationService mediaPublicationService;

    @Override
    public DispatchCallTaskResponse startSingleCall(String targetExtension) {
        return start("SINGLE", List.of(targetExtension));
    }

    @Override
    public DispatchCallTaskResponse startGroupCall(List<String> targetExtensions) {
        return start("GROUP", targetExtensions);
    }

    @Override
    public DispatchCallTaskResponse startBroadcast(Long mediaAssetId, List<String> targetExtensions) {
        DispatchOperatorExtensionResponse operator = operatorExtensionService.requireCurrent();
        EslEndpoint endpoint = endpoint(operator.getNodeId());
        Set<String> registeredExtensions = commandGateway.listRegisteredExtensions(endpoint);
        List<String> extensions = normalizeTargets(targetExtensions, null);
        List<SipAccountRealtimeResponse> accounts = validateTargets(operator.getNodeId(), extensions, registeredExtensions);
        BroadcastMedia media = resolveBroadcastMedia(mediaAssetId, operator.getNodeId());

        DispatchCallTask task = new DispatchCallTask();
        task.setBusinessCallId(UUID.randomUUID().toString());
        task.setNodeId(operator.getNodeId());
        task.setOperatorUserId(LoginHelper.getUserId());
        task.setOperatorSipAccountId(operator.getSipAccountId());
        task.setOperatorExtension(operator.getExtension());
        task.setMediaAssetId(media.mediaId());
        task.setMediaName(media.mediaName());
        task.setMediaPath(media.mediaPath());
        task.setTaskType("BROADCAST");
        task.setTaskState("STARTING");
        task.setTotalCount(accounts.size());
        task.setAnsweredCount(0);
        task.setFailedCount(0);
        task.setCancelledCount(0);
        task.setStartedAt(LocalDateTime.now());
        taskMapper.insert(task);

        List<DispatchCallTarget> targets = createTargets(task, accounts);
        int submittedCount = 0;
        for (DispatchCallTarget target : targets) {
            SipAccountRealtimeResponse account = requireTargetAccount(accounts, target);
            target.setTargetState("SUBMITTED");
            target.setSubmittedAt(LocalDateTime.now());
            targetMapper.updateById(target);
            try {
                commandGateway.originateDispatchPlayback(endpoint, task.getBusinessCallId(), target.getTargetLegUuid(),
                    target.getTargetExtension(), operator.getExtension(), media.mediaPath(),
                    task.getId(), target.getId());
                submittedCount++;
            } catch (Exception exception) {
                target.setTargetState("FAILED");
                target.setFailureReason(exception.getMessage());
                target.setEndedAt(LocalDateTime.now());
                targetMapper.updateById(target);
                log.warn("调度广播目标提交失败，taskId={}，targetId={}，targetExtension={}，targetLegUuid={}，error={}",
                    task.getId(), target.getId(), target.getTargetExtension(), target.getTargetLegUuid(), exception.getMessage());
            }
        }
        task.setTaskState(submittedCount == 0 ? "FAILED" : "RUNNING");
        if (submittedCount == 0) {
            task.setEndedAt(LocalDateTime.now());
        }
        taskMapper.updateById(task);
        recalculateTask(task.getId());
        log.info("调度预录音广播任务已提交，taskId={}，businessCallId={}，nodeId={}，operatorExtension={}，mediaId={}，mediaPath={}，targetCount={}，submittedCount={}",
            task.getId(), task.getBusinessCallId(), task.getNodeId(), task.getOperatorExtension(), media.mediaId(),
            media.mediaPath(), targets.size(), submittedCount);
        return get(task.getId());
    }

    @Override
    public DispatchCallTaskResponse startIntercom(String targetExtension) {
        return start("INTERCOM", List.of(targetExtension));
    }

    private DispatchCallTaskResponse start(String taskType, List<String> requestedExtensions) {
        DispatchOperatorExtensionResponse operator = operatorExtensionService.requireCurrent();
        EslEndpoint endpoint = endpoint(operator.getNodeId());
        Set<String> registeredExtensions = commandGateway.listRegisteredExtensions(endpoint);
        if (!SipRegistrationMatcher.isRegistered(registeredExtensions,
            operator.getExtension(), operator.getAuthUsername())) {
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
        task.setIntercomTalking(false);
        task.setTaskType(taskType);
        task.setTaskState("STARTING");
        task.setTotalCount(accounts.size());
        task.setAnsweredCount(0);
        task.setFailedCount(0);
        task.setCancelledCount(0);
        task.setStartedAt(LocalDateTime.now());
        taskMapper.insert(task);

        List<DispatchCallTarget> targets = createTargets(task, accounts);
        try {
            commandGateway.originateDispatchParticipant(endpoint, task.getBusinessCallId(), task.getOperatorLegUuid(),
                task.getConferenceName(), task.getOperatorExtension(), task.getOperatorExtension(),
                "INTERCOM".equals(taskType) ? "DISPATCH_INTERCOM_OPERATOR" : "DISPATCH_CALL_OPERATOR", task.getId(), null);
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
            SipAccountRealtimeResponse account = requireTargetAccount(accounts, target);
            target.setTargetState("SUBMITTED");
            target.setSubmittedAt(LocalDateTime.now());
            targetMapper.updateById(target);
            try {
                commandGateway.originateDispatchParticipant(endpoint, task.getBusinessCallId(), target.getTargetLegUuid(),
                    task.getConferenceName(), target.getTargetExtension(), task.getOperatorExtension(),
                    "INTERCOM".equals(taskType) ? "DISPATCH_INTERCOM_TARGET" : "DISPATCH_CALL_TARGET", task.getId(), target.getId());
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
            taskTypeLabel(taskType), task.getId(), task.getBusinessCallId(), task.getNodeId(),
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
    public void terminateBroadcast(Long taskId) {
        DispatchCallTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new ServiceException("调度广播任务不存在");
        }
        if (!"BROADCAST".equals(task.getTaskType())) {
            throw new ServiceException("当前任务不是预录音广播");
        }
        if (TERMINAL_TASK_STATES.contains(task.getTaskState())) {
            throw new ServiceException("调度广播任务已结束");
        }
        EslEndpoint endpoint = endpoint(task.getNodeId());
        int terminatedCount = 0;
        for (DispatchCallTarget target : targets(taskId)) {
            if (TERMINAL_STATES.contains(target.getTargetState())) {
                continue;
            }
            try {
                if (commandGateway.callExists(endpoint, target.getTargetLegUuid())) {
                    commandGateway.hangup(endpoint, target.getTargetLegUuid());
                }
            } catch (Exception exception) {
                log.warn("终止调度广播目标失败，继续收敛任务状态，taskId={}，targetId={}，targetLegUuid={}，error={}",
                    taskId, target.getId(), target.getTargetLegUuid(), exception.getMessage());
            }
            target.setTargetState("CANCELLED");
            target.setFailureReason("调度员终止广播");
            target.setEndedAt(LocalDateTime.now());
            targetMapper.updateById(target);
            terminatedCount++;
        }
        task.setTaskState("CANCELLED");
        task.setEndedAt(LocalDateTime.now());
        task.setCancelledCount(targets(taskId).stream()
            .mapToInt(target -> "CANCELLED".equals(target.getTargetState()) ? 1 : 0)
            .sum());
        taskMapper.updateById(task);
        log.info("调度预录音广播已终止，taskId={}，businessCallId={}，terminatedCount={}",
            taskId, task.getBusinessCallId(), terminatedCount);
    }

    @Override
    public void setIntercomTalking(Long taskId, boolean talking) {
        DispatchCallTask task = requireOwnedActiveIntercom(taskId);
        DispatchCallTarget target = targets(taskId).stream().findFirst()
            .orElseThrow(() -> new ServiceException("对讲目标不存在"));
        if (!Boolean.TRUE.equals(target.getAnswered()) || TERMINAL_STATES.contains(target.getTargetState())) {
            throw new ServiceException("目标分机尚未接听，不能控制对讲发言");
        }
        EslEndpoint endpoint = endpoint(task.getNodeId());
        if (!commandGateway.callExists(endpoint, task.getOperatorLegUuid())) {
            throw new ServiceException("调度分机对讲电话腿已结束");
        }
        if (!commandGateway.callExists(endpoint, target.getTargetLegUuid())) {
            throw new ServiceException("目标分机对讲电话腿已结束");
        }
        if (talking) {
            commandGateway.unmute(endpoint, task.getOperatorLegUuid());
        } else {
            commandGateway.mute(endpoint, task.getOperatorLegUuid());
        }
        task.setIntercomTalking(talking);
        taskMapper.updateById(task);
        log.info("调度对讲发言状态已更新，taskId={}，businessCallId={}，operatorLegUuid={}，targetLegUuid={}，talking={}",
            taskId, task.getBusinessCallId(), task.getOperatorLegUuid(), target.getTargetLegUuid(), talking);
    }

    @Override
    public void terminateIntercom(Long taskId) {
        DispatchCallTask task = requireOwnedActiveIntercom(taskId);
        EslEndpoint endpoint = endpoint(task.getNodeId());
        try {
            commandGateway.terminateConference(endpoint, task.getConferenceName());
        } catch (Exception exception) {
            log.warn("终止调度对讲会议失败，继续逐腿清理，taskId={}，conferenceName={}，error={}",
                taskId, task.getConferenceName(), exception.getMessage());
        }
        if (commandGateway.callExists(endpoint, task.getOperatorLegUuid())) {
            commandGateway.hangup(endpoint, task.getOperatorLegUuid());
        }
        for (DispatchCallTarget target : targets(taskId)) {
            if (!TERMINAL_STATES.contains(target.getTargetState())) {
                try {
                    if (commandGateway.callExists(endpoint, target.getTargetLegUuid())) {
                        commandGateway.hangup(endpoint, target.getTargetLegUuid());
                    }
                } catch (Exception exception) {
                    log.debug("调度对讲目标腿已并发结束，taskId={}，targetLegUuid={}，error={}",
                        taskId, target.getTargetLegUuid(), exception.getMessage());
                }
                target.setTargetState("CANCELLED");
                target.setFailureReason("调度员结束对讲");
                target.setEndedAt(LocalDateTime.now());
                targetMapper.updateById(target);
            }
        }
        task.setIntercomTalking(false);
        task.setTaskState("CANCELLED");
        task.setEndedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        log.info("调度对讲已结束，taskId={}，businessCallId={}，operatorLegUuid={}",
            taskId, task.getBusinessCallId(), task.getOperatorLegUuid());
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
                if (EslEventNames.CHANNEL_ANSWER.equals(event.eventName())) {
                    muteIntercomLeg(taskId, event.uuid(), "目标");
                }
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
        if (EslEventNames.CHANNEL_HANGUP.equals(event.eventName())) {
            terminateIntercomAfterTargetHangup(taskId, target.getTargetLegUuid());
        }
        recalculateTask(taskId);
    }

    private void handleOperatorEvent(Long taskId, TelephonyEvent event) {
        DispatchCallTask task = taskMapper.selectById(taskId);
        if (task == null || !event.uuid().equals(task.getOperatorLegUuid())) {
            return;
        }
        if (EslEventNames.CHANNEL_HANGUP.equals(event.eventName())) {
            if ("INTERCOM".equals(task.getTaskType())) {
                task.setIntercomTalking(false);
                taskMapper.updateById(task);
            }
            terminateTargetsAfterOperatorHangup(task);
            return;
        }
        if (EslEventNames.CHANNEL_ANSWER.equals(event.eventName())
            || EslEventNames.CHANNEL_BRIDGE.equals(event.eventName())) {
            if ("INTERCOM".equals(task.getTaskType()) && EslEventNames.CHANNEL_ANSWER.equals(event.eventName())) {
                muteIntercomLeg(taskId, event.uuid(), "调度");
            }
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
            if (operatorExtension != null && operatorExtension.equals(value)) {
                throw new ServiceException("调度分机不能呼叫自己");
            }
            extensions.add(value);
        }
        if (extensions.size() > 50) {
            throw new ServiceException("单次组呼最多选择50个分机");
        }
        return List.copyOf(extensions);
    }

    private DispatchCallTask requireOwnedActiveIntercom(Long taskId) {
        DispatchCallTask task = taskMapper.selectById(taskId);
        if (task == null || !"INTERCOM".equals(task.getTaskType())) {
            throw new ServiceException("调度对讲任务不存在");
        }
        if (!LoginHelper.getUserId().equals(task.getOperatorUserId())) {
            throw new ServiceException("只能控制本人发起的调度对讲");
        }
        if (TERMINAL_TASK_STATES.contains(task.getTaskState())) {
            throw new ServiceException("调度对讲任务已结束");
        }
        return task;
    }

    private void muteIntercomLeg(Long taskId, String legUuid, String legName) {
        DispatchCallTask task = taskMapper.selectById(taskId);
        if (task == null || !"INTERCOM".equals(task.getTaskType())) {
            return;
        }
        try {
            commandGateway.mute(endpoint(task.getNodeId()), legUuid);
            if (legUuid.equals(task.getOperatorLegUuid())) {
                task.setIntercomTalking(false);
                taskMapper.updateById(task);
            }
            log.info("调度对讲电话腿已进入静音，taskId={}，legName={}，legUuid={}", taskId, legName, legUuid);
        } catch (Exception exception) {
            log.warn("调度对讲电话腿初始化静音失败，taskId={}，legName={}，legUuid={}，error={}",
                taskId, legName, legUuid, exception.getMessage());
        }
    }

    private void terminateIntercomAfterTargetHangup(Long taskId, String targetLegUuid) {
        DispatchCallTask task = taskMapper.selectById(taskId);
        if (task == null || !"INTERCOM".equals(task.getTaskType()) || TERMINAL_TASK_STATES.contains(task.getTaskState())) {
            return;
        }
        task.setIntercomTalking(false);
        taskMapper.updateById(task);
        try {
            commandGateway.terminateConference(endpoint(task.getNodeId()), task.getConferenceName());
            log.info("对讲目标分机挂机，已结束对讲会议，taskId={}，targetLegUuid={}，operatorLegUuid={}",
                taskId, targetLegUuid, task.getOperatorLegUuid());
        } catch (Exception exception) {
            log.debug("对讲目标分机挂机时会议已并发结束，taskId={}，targetLegUuid={}，error={}",
                taskId, targetLegUuid, exception.getMessage());
        }
    }

    private String taskTypeLabel(String taskType) {
        if ("GROUP".equals(taskType)) return "组呼";
        if ("INTERCOM".equals(taskType)) return "对讲";
        return "单呼";
    }

    private List<SipAccountRealtimeResponse> validateTargets(Long nodeId, List<String> extensions,
                                                              Set<String> registeredExtensions) {
        List<SipAccountRealtimeResponse> accounts = new ArrayList<>();
        for (String extension : extensions) {
            SipAccountRealtimeResponse account = sipAccountQueryService.findEnabledByNodeAndExtension(nodeId, extension);
            if (account == null) {
                throw new ServiceException("目标分机不存在、已停用或不属于当前 FreeSWITCH 节点：" + extension);
            }
            if (!SipRegistrationMatcher.isRegistered(registeredExtensions,
                account.getExtension(), account.getAuthUsername())) {
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

    private List<DispatchCallTarget> createTargets(DispatchCallTask task, List<SipAccountRealtimeResponse> accounts) {
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
        return targets;
    }

    private SipAccountRealtimeResponse requireTargetAccount(List<SipAccountRealtimeResponse> accounts,
                                                             DispatchCallTarget target) {
        return accounts.stream()
            .filter(account -> account.getSipAccountId().equals(target.getSipAccountId()))
            .findFirst()
            .orElseThrow(() -> new ServiceException("调度目标 SIP 账号不存在：" + target.getTargetExtension()));
    }

    private BroadcastMedia resolveBroadcastMedia(Long mediaAssetId, Long nodeId) {
        MediaAssetResponse media = mediaAssetService.get(mediaAssetId);
        if (!Boolean.TRUE.equals(media.getEnabled())) {
            throw new ServiceException("广播声音媒体已停用");
        }
        if ("CALL_RECORDING".equals(media.getCategory()) || "VOICEMAIL_RECORDING".equals(media.getCategory())) {
            throw new ServiceException("通话录音和语音留言不能用于调度广播");
        }
        if (!List.of("PUBLISHED", "PARTIAL").contains(media.getPublishStatus())) {
            throw new ServiceException("广播声音媒体尚未发布");
        }
        List<MediaSyncResponse> syncRecords = mediaPublicationService.syncs(mediaAssetId);
        MediaSyncResponse sync = syncRecords.stream()
            .filter(item -> nodeId.equals(item.getNodeId()))
            .filter(item -> "SUCCESS".equals(item.getStatus()))
            .filter(item -> media.getLatestVersionNo() != null
                && media.getLatestVersionNo().equals(item.getVersionNo()))
            .filter(item -> item.getTargetPath() != null && !item.getTargetPath().isBlank())
            .findFirst()
            .orElseThrow(() -> {
                String nodeSyncSummary = syncRecords.stream()
                    .filter(item -> nodeId.equals(item.getNodeId()))
                    .map(item -> "v" + item.getVersionNo() + ":" + item.getStatus())
                    .distinct()
                    .toList()
                    .toString();
                log.warn("广播声音媒体无可用节点文件，mediaId={}，nodeId={}，latestVersionNo={}，publishStatus={}，nodeSyncs={}",
                    mediaAssetId, nodeId, media.getLatestVersionNo(), media.getPublishStatus(), nodeSyncSummary);
                return new ServiceException("广播声音媒体最新发布版本尚未同步到当前 FreeSWITCH 节点");
            });
        return new BroadcastMedia(media.getId(), media.getAssetName(), sync.getTargetPath());
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
        if ("CANCELLED".equals(task.getTaskState())) {
            taskMapper.updateById(task);
            return;
        }
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
        response.setMediaAssetId(task.getMediaAssetId());
        response.setMediaName(task.getMediaName());
        response.setMediaPath(task.getMediaPath());
        response.setIntercomTalking(task.getIntercomTalking());
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

    private record BroadcastMedia(Long mediaId, String mediaName, String mediaPath) {
    }
}
