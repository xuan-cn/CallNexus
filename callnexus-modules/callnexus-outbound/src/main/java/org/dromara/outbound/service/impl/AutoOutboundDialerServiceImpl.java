package org.dromara.outbound.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.service.AiRealtimeDialplanService;
import org.dromara.call.domain.CallOriginateContext;
import org.dromara.call.domain.EslEndpoint;
import org.dromara.call.domain.OutboundRoute;
import org.dromara.call.service.CallBusinessAssociationService;
import org.dromara.call.service.TelephonyCommandGateway;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.outbound.domain.AutoOutboundDispatch;
import org.dromara.outbound.domain.OutboundAttempt;
import org.dromara.outbound.domain.OutboundMember;
import org.dromara.outbound.domain.OutboundTask;
import org.dromara.outbound.mapper.AutoOutboundDispatchMapper;
import org.dromara.outbound.mapper.OutboundAttemptMapper;
import org.dromara.outbound.mapper.OutboundMemberMapper;
import org.dromara.outbound.mapper.OutboundTaskMapper;
import org.dromara.outbound.service.AutoOutboundDialerService;
import org.dromara.outbound.service.OutboundAutomaticRetryService;
import org.dromara.outbound.service.OutboundBlacklistChecker;
import org.dromara.outbound.service.OutboundBlacklistMemberSyncService;
import org.dromara.customer.customer.service.CustomerApplicationService;
import org.dromara.outbound.service.model.AutoOutboundDialerResult;
import org.dromara.resource.ivr.service.IvrDialplanQueryService;
import org.dromara.resource.node.domain.response.FreeSwitchNodeConnectionResponse;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.dromara.resource.outboundauth.domain.OutboundAuthorizationCommand;
import org.dromara.resource.outboundauth.domain.OutboundAuthorizationResult;
import org.dromara.resource.outboundauth.service.OutboundAuthorizationService;
import org.dromara.resource.phone.domain.response.PhoneNumberResponse;
import org.dromara.resource.phone.service.PhoneNumberApplicationService;
import org.dromara.system.callcenterconfig.service.CallCenterConfigService;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutoOutboundDialerServiceImpl implements AutoOutboundDialerService {
    private static final int DEFAULT_BATCH_SIZE = 20;
    private static final int DEFAULT_LEASE_SECONDS = 90;
    private static final int DEFAULT_CALL_LEASE_MINUTES = 120;

    private final AutoOutboundDispatchMapper dispatchMapper;
    private final OutboundTaskMapper taskMapper;
    private final OutboundMemberMapper memberMapper;
    private final OutboundAttemptMapper attemptMapper;
    private final PhoneNumberApplicationService phoneNumberService;
    private final FreeSwitchNodeQueryService nodeQueryService;
    private final OutboundAuthorizationService authorizationService;
    private final IvrDialplanQueryService ivrDialplanQueryService;
    private final AiRealtimeDialplanService aiRealtimeDialplanService;
    private final TelephonyCommandGateway commandGateway;
    private final CallBusinessAssociationService associationService;
    private final OutboundAutomaticRetryService automaticRetryService;
    private final OutboundBlacklistChecker blacklistChecker;
    private final OutboundBlacklistMemberSyncService blacklistMemberSyncService;
    private final CallCenterConfigService configService;
    private final CustomerApplicationService customerService;

    private final String owner = ManagementFactory.getRuntimeMXBean().getName() + "-dialer-"
        + UUID.randomUUID().toString().substring(0, 8);

    @Override
    public AutoOutboundDialerResult execute() {
        List<String> tenantIds = TenantHelper.ignore(dispatchMapper::listReadyTenantIds);
        Counter total = new Counter();
        for (String tenantId : tenantIds) {
            TenantHelper.dynamic(tenantId, () -> consumeTenant(total));
        }
        AutoOutboundDialerResult result = new AutoOutboundDialerResult(
            tenantIds.size(), total.claimed, total.submitted, total.cancelled, total.failed);
        log.info("自动外呼待拨消费执行完成，owner={}，{}", owner, result.summary());
        return result;
    }

    private void consumeTenant(Counter counter) {
        int batchSize = configInt("autoOutbound.dialerBatchSize", DEFAULT_BATCH_SIZE);
        List<AutoOutboundDispatch> candidates = dispatchMapper.selectList(
            new LambdaQueryWrapper<AutoOutboundDispatch>()
                .eq(AutoOutboundDispatch::getStatus, "READY")
                .orderByAsc(AutoOutboundDispatch::getScheduledAt)
                .last("LIMIT " + batchSize));
        for (AutoOutboundDispatch candidate : candidates) {
            LocalDateTime now = LocalDateTime.now();
            if (dispatchMapper.claimReady(candidate.getId(), TenantHelper.getTenantId(), owner, now,
                now.plusSeconds(configInt("autoOutbound.dialerLeaseSeconds", DEFAULT_LEASE_SECONDS))) == 0) {
                continue;
            }
            counter.claimed++;
            AutoOutboundDispatch dispatch = dispatchMapper.selectById(candidate.getId());
            try {
                if (process(dispatch)) counter.submitted++; else counter.cancelled++;
            } catch (Exception exception) {
                counter.failed++;
                fail(dispatch, exception);
            }
        }
    }

    private boolean process(AutoOutboundDispatch dispatch) {
        OutboundTask task = taskMapper.selectById(dispatch.getTaskId());
        OutboundMember member = memberMapper.selectById(dispatch.getMemberId());
        if (task == null || member == null || !"RUNNING".equals(task.getStatus())) {
            cancel(dispatch, member, "任务已暂停、停止或不存在");
            return false;
        }
        var blacklistMatch = blacklistChecker.check(task.getId(), member.getPhoneNumber());
        if (blacklistMatch != null) {
            blacklistMemberSyncService.blockMember(member, blacklistMatch);
            cancel(dispatch, member, "号码已命中外呼黑名单");
            return false;
        }
        PhoneNumberResponse caller = task.getCallerNumberId() == null ? null : phoneNumberService.get(task.getCallerNumberId());
        if (caller == null || caller.getNodeId() == null || !Boolean.TRUE.equals(caller.getEnabled())) {
            throw new ServiceException("自动外呼任务必须配置当前节点已启用的主叫号码");
        }
        FreeSwitchNodeConnectionResponse node = nodeQueryService.getEnabledConnection(caller.getNodeId());
        EslEndpoint endpoint = new EslEndpoint(node.getEslHost(), node.getEslPort(), node.getEslPassword(), node.getSipDomain());
        OutboundAuthorizationResult authorization = authorizationService.authorize(new OutboundAuthorizationCommand(
            TenantHelper.getTenantId(), "AUTO_OUTBOUND", caller.getNodeId(), node.getSipDomain(), null,
            null, null, task.getSkillGroupId(), caller.getNumber(), member.getPhoneNumber(), caller.getId(),
            task.getId(), member.getId(), member.getCustomerId()));
        if (!authorization.allowed()) throw new ServiceException(authorization.rejectMessage());
        if (!authorization.external() || authorization.outboundRoute() == null) {
            throw new ServiceException("自动外呼名单只允许拨打外线号码");
        }
        if (dispatch.getBusinessCallId() != null && !dispatch.getBusinessCallId().isBlank()) {
            return resumeSubmittedDispatch(dispatch, member, endpoint);
        }
        String answeredDestination = answeredDestination(task, caller.getNodeId());
        Map<String, String> variables = targetVariables(task);
        String businessCallId = UUID.randomUUID().toString();
        OutboundAttempt attempt = createAttempt(task, member, dispatch, businessCallId);
        bindDispatchAndMember(dispatch, member, attempt, businessCallId);
        associationService.associateCustomer(businessCallId, member.getCustomerId());
        commandGateway.originateAgentless(endpoint, businessCallId, authorization.normalizedCallee(),
            toRoute(authorization), new CallOriginateContext(businessCallId, member.getCustomerId(), task.getId(),
                member.getId(), caller.getId(), task.getSkillGroupId(), null), answeredDestination, variables);
        extendCallLease(dispatch.getId());
        log.info("自动外呼已提交，dispatchId={}，attemptId={}，businessCallId={}，taskId={}，memberId={}，mode={}，target={}",
            dispatch.getId(), attempt.getId(), businessCallId, task.getId(), member.getId(),
            task.getDialMode(), answeredDestination);
        return true;
    }

    private String answeredDestination(OutboundTask task, Long nodeId) {
        if ("AGENTLESS_IVR".equals(task.getDialMode())) {
            String destination = ivrDialplanQueryService.resolvePublishedStartDestination(
                TenantHelper.getTenantId(), task.getTargetId(), nodeId);
            if (destination == null || destination.isBlank()) {
                throw new ServiceException("自动外呼关联的 IVR 流程未发布或不属于当前节点");
            }
            return destination;
        }
        if ("AGENTLESS_AI".equals(task.getDialMode())) {
            aiRealtimeDialplanService.validate(task.getTargetId());
            return "callnexus_auto_ai_" + task.getTargetId();
        }
        throw new ServiceException("渐进式自动外呼将在后续阶段接入坐席容量，本阶段仅支持 AI 和 IVR");
    }

    private Map<String, String> targetVariables(OutboundTask task) {
        Map<String, String> variables = new HashMap<>();
        variables.put("callnexus_auto_outbound", "true");
        variables.put("callnexus_auto_outbound_mode", task.getDialMode());
        if ("AGENTLESS_IVR".equals(task.getDialMode())) {
            variables.put("callnexus_ivr_flow_id", String.valueOf(task.getTargetId()));
        }
        if (task.getSkillGroupId() != null) {
            variables.put("callnexus_auto_outbound_skill_group_id", String.valueOf(task.getSkillGroupId()));
        }
        return variables;
    }

    private boolean resumeSubmittedDispatch(AutoOutboundDispatch dispatch, OutboundMember member,
                                            EslEndpoint endpoint) {
        if (!commandGateway.callExists(endpoint, dispatch.getBusinessCallId())) {
            throw new ServiceException("已提交的自动外呼电话腿不存在，按失败结果重新调度");
        }
        extendCallLease(dispatch.getId());
        memberMapper.update(null, new LambdaUpdateWrapper<OutboundMember>()
            .eq(OutboundMember::getId, member.getId())
            .in(OutboundMember::getStatus, List.of("SCHEDULED", "DIALING"))
            .set(OutboundMember::getStatus, "DIALING")
            .set(OutboundMember::getBusinessCallId, dispatch.getBusinessCallId()));
        log.info("自动外呼已存在活动电话腿，仅续租不重复拨号，dispatchId={}，businessCallId={}",
            dispatch.getId(), dispatch.getBusinessCallId());
        return true;
    }

    private void extendCallLease(Long dispatchId) {
        LocalDateTime now = LocalDateTime.now();
        dispatchMapper.update(null, new LambdaUpdateWrapper<AutoOutboundDispatch>()
            .eq(AutoOutboundDispatch::getId, dispatchId)
            .eq(AutoOutboundDispatch::getStatus, "PROCESSING")
            .set(AutoOutboundDispatch::getLeaseOwner, owner)
            .set(AutoOutboundDispatch::getLeaseExpiresAt,
                now.plusMinutes(configInt("autoOutbound.callLeaseMinutes", DEFAULT_CALL_LEASE_MINUTES))));
    }

    private OutboundAttempt createAttempt(OutboundTask task, OutboundMember member,
                                           AutoOutboundDispatch dispatch, String businessCallId) {
        OutboundAttempt attempt = new OutboundAttempt();
        attempt.setTaskId(task.getId());
        attempt.setMemberId(member.getId());
        attempt.setCustomerId(member.getCustomerId());
        attempt.setTaskName(task.getTaskName());
        attempt.setCustomerName(member.getCustomerName());
        attempt.setPhoneNumber(member.getPhoneNumber());
        attempt.setAttemptNo(dispatch.getAttemptNo());
        attempt.setBusinessCallId(businessCallId);
        attempt.setStatus("DIALING");
        attempt.setStartedAt(LocalDateTime.now());
        attempt.setDurationSeconds(0);
        attempt.setBillableSeconds(0);
        attemptMapper.insert(attempt);
        return attempt;
    }

    private void bindDispatchAndMember(AutoOutboundDispatch dispatch, OutboundMember member,
                                       OutboundAttempt attempt, String businessCallId) {
        dispatchMapper.update(null, new LambdaUpdateWrapper<AutoOutboundDispatch>()
            .eq(AutoOutboundDispatch::getId, dispatch.getId())
            .eq(AutoOutboundDispatch::getStatus, "PROCESSING")
            .eq(AutoOutboundDispatch::getLeaseOwner, owner)
            .set(AutoOutboundDispatch::getAttemptId, attempt.getId())
            .set(AutoOutboundDispatch::getBusinessCallId, businessCallId));
        dispatch.setAttemptId(attempt.getId());
        dispatch.setBusinessCallId(businessCallId);
        int updated = memberMapper.update(null, new LambdaUpdateWrapper<OutboundMember>()
            .eq(OutboundMember::getId, member.getId())
            .eq(OutboundMember::getScheduleKey, dispatch.getDispatchKey())
            .eq(OutboundMember::getStatus, "SCHEDULED")
            .set(OutboundMember::getStatus, "DIALING")
            .set(OutboundMember::getBusinessCallId, businessCallId)
            .set(OutboundMember::getAttemptCount, dispatch.getAttemptNo()));
        if (updated == 0) throw new ServiceException("名单状态已变化，取消本次拨打");
    }

    private OutboundRoute toRoute(OutboundAuthorizationResult authorization) {
        var source = authorization.outboundRoute();
        return OutboundRoute.external(source.getGatewayCode(), source.getNumber(), source.getGatewayAccessMode(),
            source.getRegisteredIdentity(), source.getGatewaySipProfile(), source.getSipDomain());
    }

    private void cancel(AutoOutboundDispatch dispatch, OutboundMember member, String reason) {
        LocalDateTime now = LocalDateTime.now();
        dispatchMapper.update(null, new LambdaUpdateWrapper<AutoOutboundDispatch>()
            .eq(AutoOutboundDispatch::getId, dispatch.getId())
            .eq(AutoOutboundDispatch::getStatus, "PROCESSING")
            .set(AutoOutboundDispatch::getStatus, "CANCELLED")
            .set(AutoOutboundDispatch::getCompletedAt, now)
            .set(AutoOutboundDispatch::getFailureReason, reason)
            .set(AutoOutboundDispatch::getLeaseOwner, null)
            .set(AutoOutboundDispatch::getLeaseExpiresAt, null));
        if (member != null && "SCHEDULED".equals(member.getStatus())) {
            memberMapper.update(null, new LambdaUpdateWrapper<OutboundMember>()
                .eq(OutboundMember::getId, member.getId())
                .eq(OutboundMember::getScheduleKey, dispatch.getDispatchKey())
                .eq(OutboundMember::getStatus, "SCHEDULED")
                .set(OutboundMember::getStatus, dispatch.getPreviousMemberStatus())
                .set(OutboundMember::getScheduleKey, null)
                .set(OutboundMember::getScheduledAt, null)
                .set(OutboundMember::getLeaseExpiresAt, null));
        }
    }

    private void fail(AutoOutboundDispatch dispatch, Exception exception) {
        AutoOutboundDispatch persisted = dispatchMapper.selectById(dispatch.getId());
        if (persisted != null) dispatch = persisted;
        String reason = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        LocalDateTime now = LocalDateTime.now();
        if (dispatch.getBusinessCallId() == null) {
            cancel(dispatch, memberMapper.selectById(dispatch.getMemberId()), reason);
            log.error("自动外呼准备失败，dispatchId={}，taskId={}，memberId={}，error={}",
                dispatch.getId(), dispatch.getTaskId(), dispatch.getMemberId(), reason, exception);
            return;
        }
        dispatchMapper.update(null, new LambdaUpdateWrapper<AutoOutboundDispatch>()
            .eq(AutoOutboundDispatch::getId, dispatch.getId())
            .set(AutoOutboundDispatch::getStatus, "COMPLETED")
            .set(AutoOutboundDispatch::getCompletedAt, now)
            .set(AutoOutboundDispatch::getFailureReason, reason)
            .set(AutoOutboundDispatch::getHangupCause, "ORIGINATE_FAILED")
            .set(AutoOutboundDispatch::getLeaseOwner, null)
            .set(AutoOutboundDispatch::getLeaseExpiresAt, null));
        if (dispatch.getAttemptId() != null) {
            attemptMapper.update(null, new LambdaUpdateWrapper<OutboundAttempt>()
                .eq(OutboundAttempt::getId, dispatch.getAttemptId())
                .set(OutboundAttempt::getStatus, "ENDED")
                .set(OutboundAttempt::getEndedAt, now)
                .set(OutboundAttempt::getSuggestedResultCode, "OTHER")
                .set(OutboundAttempt::getHangupCause, "ORIGINATE_FAILED")
                .set(OutboundAttempt::getFailureCategory, "PLATFORM")
                .set(OutboundAttempt::getRetryable, true)
                .set(OutboundAttempt::getResultRemark, reason));
            OutboundAttempt attempt = attemptMapper.selectById(dispatch.getAttemptId());
            OutboundTask task = taskMapper.selectById(dispatch.getTaskId());
            if (attempt != null && attempt.getCustomerId() != null && task != null
                && !Boolean.FALSE.equals(task.getResultWritebackEnabled())) {
                customerService.recordOutboundResult(attempt.getCustomerId(), attempt.getId(),
                    "自动外呼任务“" + task.getTaskName() + "”呼叫提交失败：" + reason,
                    task.getFailedTag());
            }
        }
        automaticRetryService.applySystemSuggestion(dispatch.getMemberId(), dispatch.getBusinessCallId(), "OTHER");
        log.error("自动外呼提交失败，dispatchId={}，taskId={}，memberId={}，error={}",
            dispatch.getId(), dispatch.getTaskId(), dispatch.getMemberId(), reason, exception);
    }

    private int configInt(String key, int defaultValue) {
        Integer value = configService.getIntOrDefault(key, defaultValue);
        return value == null || value < 1 ? defaultValue : value;
    }

    private static final class Counter {
        private int claimed;
        private int submitted;
        private int cancelled;
        private int failed;
    }
}
