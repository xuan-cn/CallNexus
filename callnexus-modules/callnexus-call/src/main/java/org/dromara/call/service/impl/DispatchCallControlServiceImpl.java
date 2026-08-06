package org.dromara.call.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.call.domain.CallLeg;
import org.dromara.call.domain.CallBridge;
import org.dromara.call.domain.CallSession;
import org.dromara.call.domain.EslEndpoint;
import org.dromara.call.domain.event.CallSupervisionLifecycleEvent;
import org.dromara.call.mapper.CallLegMapper;
import org.dromara.call.mapper.CallBridgeMapper;
import org.dromara.call.mapper.CallSessionMapper;
import org.dromara.call.domain.response.DispatchOperatorExtensionResponse;
import org.dromara.call.service.DispatchCallControlService;
import org.dromara.call.service.DispatchOperatorExtensionService;
import org.dromara.call.service.TelephonyCommandGateway;
import org.dromara.agent.domain.AgentPresenceStatus;
import org.dromara.agent.domain.response.CurrentAgentResponse;
import org.dromara.agent.service.AgentSessionApplicationService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.node.domain.response.FreeSwitchNodeConnectionResponse;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class DispatchCallControlServiceImpl implements DispatchCallControlService {
    private static final long TRANSFER_MEDIA_RECOVERY_DELAY_MILLIS = 200L;

    private final CallSessionMapper sessionMapper;
    private final CallLegMapper legMapper;
    private final CallBridgeMapper bridgeMapper;
    private final FreeSwitchNodeQueryService nodeQueryService;
    private final TelephonyCommandGateway commandGateway;
    private final DispatchOperatorExtensionService operatorExtensionService;
    private final AgentSessionApplicationService agentSessionApplicationService;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void forceHangup(String businessCallId) {
        forceHangupInternal(businessCallId, null);
    }

    private void forceHangupInternal(String businessCallId, DispatchOperatorExtensionResponse supervisor) {
        CallSession session = requireActiveSession(businessCallId);
        EslEndpoint endpoint = endpoint(session.getNodeId());
        List<CallLeg> activeLegs = activeLegs(session.getId());
        if (activeLegs.isEmpty()) {
            throw new ServiceException("当前业务通话没有活动电话腿，无法执行强制挂断");
        }
        activeLegs.stream()
            .filter(leg -> "CUSTOMER".equals(leg.getLegRole()))
            .findFirst()
            .ifPresent(leg -> setSatisfactionSkip(endpoint, businessCallId, leg.getLegUuid()));

        int accepted = 0;
        for (CallLeg leg : activeLegs.stream().sorted(Comparator.comparingInt(this::hangupPriority)).toList()) {
            if (!commandGateway.callExists(endpoint, leg.getLegUuid())) {
                continue;
            }
            try {
                commandGateway.hangup(endpoint, leg.getLegUuid());
                accepted++;
                log.info("调度强制挂断电话腿命令已提交，businessCallId={}，sessionId={}，nodeId={}，legUuid={}，legRole={}，agentExtension={}",
                    businessCallId, session.getId(), session.getNodeId(), leg.getLegUuid(), leg.getLegRole(), leg.getAgentExtension());
            } catch (Exception exception) {
                log.warn("调度强制挂断电话腿失败，继续处理其他电话腿，businessCallId={}，legUuid={}，error={}",
                    businessCallId, leg.getLegUuid(), exception.getMessage());
            }
        }
        if (accepted == 0) {
            throw new ServiceException("FreeSWITCH 中已找不到该通话的活动电话腿");
        }
        log.info("调度强制挂断整通电话完成，businessCallId={}，sessionId={}，nodeId={}，acceptedLegCount={}",
            businessCallId, session.getId(), session.getNodeId(), accepted);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation", "FORCE_HANGUP");
        payload.put("status", "ACCEPTED");
        payload.put("accepted_leg_count", accepted);
        payload.put("supervisor_agent_id", supervisor == null ? null : supervisor.getUserId());
        payload.put("supervisor_extension", supervisor == null ? null : supervisor.getExtension());
        publishSupervisionEvent("call.force_hangup", session, payload);
    }

    @Override
    public void forceTransferToExtension(String businessCallId, String targetExtension) {
        CallSession session = requireActiveSession(businessCallId);
        boolean consulting = bridgeMapper.exists(new LambdaQueryWrapper<CallBridge>()
            .eq(CallBridge::getSessionId, session.getId())
            .eq(CallBridge::getBridgeType, "CONSULT")
            .eq(CallBridge::getBridgeState, "BRIDGED"));
        if (consulting) {
            throw new ServiceException("当前通话正在咨询中，请先完成或取消咨询后再强制转接");
        }
        EslEndpoint endpoint = endpoint(session.getNodeId());
        List<CallLeg> customerLegs = activeLegs(session.getId()).stream()
            .filter(leg -> "CUSTOMER".equals(leg.getLegRole()))
            .toList();
        if (customerLegs.size() != 1) {
            throw new ServiceException(customerLegs.isEmpty()
                ? "当前业务通话没有活动客户腿，无法强制转接"
                : "当前业务通话存在多个活动客户腿，无法确定强制转接目标");
        }
        CallLeg customerLeg = customerLegs.get(0);
        if (!commandGateway.callExists(endpoint, customerLeg.getLegUuid())) {
            throw new ServiceException("客户电话腿已在 FreeSWITCH 中结束，无法强制转接");
        }
        commandGateway.setCallVariable(endpoint, customerLeg.getLegUuid(), "callnexus_satisfaction_skip", "true");
        commandGateway.setCallVariable(endpoint, customerLeg.getLegUuid(), "hangup_after_bridge", "true");
        commandGateway.setCallVariable(endpoint, customerLeg.getLegUuid(), "park_after_bridge", "false");
        tryCommand(() -> commandGateway.unhold(endpoint, customerLeg.getLegUuid()), "取消客户腿保持", businessCallId);
        tryCommand(() -> commandGateway.recoverMedia(endpoint, customerLeg.getLegUuid()), "恢复客户腿媒体", businessCallId);
        waitForMediaRecovery();
        commandGateway.blindTransfer(endpoint, customerLeg.getLegUuid(), targetExtension);
        log.info("调度强制转接到分机命令已提交，businessCallId={}，sessionId={}，nodeId={}，customerLegUuid={}，targetExtension={}",
            businessCallId, session.getId(), session.getNodeId(), customerLeg.getLegUuid(), targetExtension);
    }

    @Override
    public String startMonitor(String businessCallId, String targetExtension) {
        return startSupervision(businessCallId, targetExtension, SupervisionMode.MONITOR);
    }

    @Override
    public String startMonitor(String businessCallId, String targetExtension, Long supervisorAgentId) {
        return startSupervision(businessCallId, targetExtension, SupervisionMode.MONITOR,
            requireAgentSupervisor(supervisorAgentId, true, true));
    }

    @Override
    public void stopMonitor(String businessCallId, Long supervisorAgentId) {
        stopSupervision(businessCallId, supervisorAgentId, SupervisionMode.MONITOR);
    }

    @Override
    public String startWhisper(String businessCallId, String targetExtension) {
        return startSupervision(businessCallId, targetExtension, SupervisionMode.WHISPER);
    }

    @Override
    public String startWhisper(String businessCallId, String targetExtension, Long supervisorAgentId) {
        return startSupervision(businessCallId, targetExtension, SupervisionMode.WHISPER,
            requireAgentSupervisor(supervisorAgentId, true, true));
    }

    @Override
    public void stopWhisper(String businessCallId, Long supervisorAgentId) {
        stopSupervision(businessCallId, supervisorAgentId, SupervisionMode.WHISPER);
    }

    @Override
    public String startBarge(String businessCallId, String targetExtension) {
        return startSupervision(businessCallId, targetExtension, SupervisionMode.BARGE);
    }

    @Override
    public String startBarge(String businessCallId, String targetExtension, Long supervisorAgentId) {
        return startSupervision(businessCallId, targetExtension, SupervisionMode.BARGE,
            requireAgentSupervisor(supervisorAgentId, true, true));
    }

    @Override
    public void stopBarge(String businessCallId, Long supervisorAgentId) {
        stopSupervision(businessCallId, supervisorAgentId, SupervisionMode.BARGE);
    }

    @Override
    public void forceHangup(String businessCallId, Long supervisorAgentId) {
        forceHangupInternal(businessCallId, requireAgentSupervisor(supervisorAgentId, true, false));
    }

    private String startSupervision(String businessCallId, String targetExtension, SupervisionMode mode) {
        return startSupervision(businessCallId, targetExtension, mode, operatorExtensionService.requireCurrent());
    }

    private String startSupervision(String businessCallId, String targetExtension, SupervisionMode mode,
                                    DispatchOperatorExtensionResponse supervisor) {
        CallSession session = requireActiveSession(businessCallId);
        if (isOperatorExtensionBusy(supervisor)) {
            throw new ServiceException("当前调度分机已有活动通话，不能执行调度通话控制");
        }
        if (!session.getNodeId().equals(supervisor.getNodeId())) {
            throw new ServiceException("调度员分机与目标通话不在同一 FreeSWITCH 节点");
        }
        if (supervisor.getExtension().equals(targetExtension)) {
            throw new ServiceException("不能对当前调度员自己的分机执行调度通话控制");
        }
        List<CallLeg> targetLegs = activeLegs(session.getId()).stream()
            .filter(leg -> targetExtension.equals(leg.getEndpointExtension()))
            .filter(leg -> isControllableEndpointRole(leg.getLegRole()))
            .toList();
        if (targetLegs.size() != 1) {
            throw new ServiceException(targetLegs.isEmpty()
                ? "目标分机当前没有可调度的活动电话腿"
                : "目标分机存在多个活动电话腿，无法确定调度目标");
        }
        CallLeg targetLeg = targetLegs.get(0);
        EslEndpoint endpoint = endpoint(session.getNodeId());
        if (!commandGateway.callExists(endpoint, targetLeg.getLegUuid())) {
            throw new ServiceException("目标分机电话腿已在 FreeSWITCH 中结束");
        }
        if (!SipRegistrationMatcher.isRegistered(commandGateway.listRegisteredExtensions(endpoint),
            supervisor.getExtension(), supervisor.getAuthUsername())) {
            throw new ServiceException("当前调度员分机未在 FreeSWITCH 注册");
        }
        String supervisionLegUuid = UUID.randomUUID().toString();
        switch (mode) {
            case MONITOR -> commandGateway.originateMonitor(endpoint, businessCallId, supervisionLegUuid,
                supervisor.getExtension(), targetLeg.getLegUuid(), targetExtension);
            case WHISPER -> commandGateway.originateWhisper(endpoint, businessCallId, supervisionLegUuid,
                supervisor.getExtension(), targetLeg.getLegUuid(), targetExtension);
            case BARGE -> commandGateway.originateBarge(endpoint, businessCallId, supervisionLegUuid,
                supervisor.getExtension(), targetLeg.getLegUuid(), targetExtension);
        }
        log.info("调度{}命令已提交，businessCallId={}，sessionId={}，nodeId={}，supervisionLegUuid={}，operatorUserId={}，supervisorExtension={}，targetEndpointLegUuid={}，targetExtension={}",
            mode.label, businessCallId, session.getId(), session.getNodeId(), supervisionLegUuid, supervisor.getUserId(),
            supervisor.getExtension(), targetLeg.getLegUuid(), targetExtension);
        Map<String, Object> payload = supervisionPayload(mode, supervisor, targetExtension);
        payload.put("status", "ACCEPTED");
        publishSupervisionEvent(mode.startedEventType, session, payload);
        return supervisionLegUuid;
    }

    private void stopSupervision(String businessCallId, Long supervisorAgentId, SupervisionMode mode) {
        DispatchOperatorExtensionResponse supervisor = requireAgentSupervisor(supervisorAgentId, false, false);
        CallSession session = requireActiveSession(businessCallId);
        if (!session.getNodeId().equals(supervisor.getNodeId())) {
            throw new ServiceException("监督坐席分机与目标通话不在同一 FreeSWITCH 节点");
        }
        List<CallLeg> supervisionLegs = activeLegs(session.getId()).stream()
            .filter(leg -> mode.name().equals(leg.getLegRole()))
            .filter(leg -> supervisorAgentId.equals(leg.getAgentId())
                || supervisor.getExtension().equals(leg.getEndpointExtension()))
            .toList();
        if (supervisionLegs.isEmpty()) {
            throw new ServiceException("当前坐席没有正在进行的" + mode.label + "通话");
        }
        EslEndpoint endpoint = endpoint(session.getNodeId());
        int accepted = 0;
        for (CallLeg leg : supervisionLegs) {
            if (!commandGateway.callExists(endpoint, leg.getLegUuid())) {
                continue;
            }
            commandGateway.hangup(endpoint, leg.getLegUuid());
            accepted++;
        }
        if (accepted == 0) {
            throw new ServiceException("FreeSWITCH 中已找不到活动的" + mode.label + "电话腿");
        }
        log.info("调度{}停止命令已提交，businessCallId={}，sessionId={}，nodeId={}，supervisorAgentId={}，supervisorExtension={}，acceptedLegCount={}",
            mode.label, businessCallId, session.getId(), session.getNodeId(), supervisorAgentId,
            supervisor.getExtension(), accepted);
        Map<String, Object> payload = supervisionPayload(mode, supervisor, null);
        payload.put("status", "STOPPED");
        payload.put("accepted_leg_count", accepted);
        publishSupervisionEvent(mode.stoppedEventType, session, payload);
    }

    private DispatchOperatorExtensionResponse requireAgentSupervisor(Long supervisorAgentId, boolean requireSignedIn,
                                                                     boolean requireIdle) {
        if (supervisorAgentId == null) {
            throw new ServiceException("监督坐席 ID 不能为空");
        }
        CurrentAgentResponse agent = agentSessionApplicationService.get(supervisorAgentId);
        if (!agent.isConfigured() || agent.getNodeId() == null || agent.getExtension() == null
            || agent.getExtension().isBlank()) {
            throw new ServiceException("监督坐席未绑定可用的 SIP 分机");
        }
        if (requireSignedIn && (agent.getStatus() == null || agent.getStatus() == AgentPresenceStatus.OFFLINE)) {
            throw new ServiceException("监督坐席未签入，请先签入");
        }
        if (requireIdle && agent.getStatus() != AgentPresenceStatus.IDLE) {
            throw new ServiceException("监督坐席当前不是示闲状态，无法发起通话监督");
        }
        DispatchOperatorExtensionResponse response = new DispatchOperatorExtensionResponse();
        response.setConfigured(true);
        response.setUserId(agent.getUserId());
        response.setSipAccountId(agent.getSipAccountId());
        response.setNodeId(agent.getNodeId());
        response.setExtension(agent.getExtension());
        response.setAuthUsername(agent.getAuthUsername());
        response.setDisplayName(agent.getSipDisplayName());
        response.setDomain(agent.getSipDomain());
        return response;
    }

    @Override
    public String pickupRingingCall(String businessCallId, String targetExtension) {
        CallSession session = requireActiveSession(businessCallId);
        DispatchOperatorExtensionResponse supervisor = operatorExtensionService.requireCurrent();
        if (isOperatorExtensionBusy(supervisor)) {
            throw new ServiceException("当前调度员已有活动通话，不能执行强接");
        }
        if (!session.getNodeId().equals(supervisor.getNodeId())) {
            throw new ServiceException("调度员分机与目标振铃通话不在同一 FreeSWITCH 节点");
        }
        if (supervisor.getExtension().equals(targetExtension)) {
            throw new ServiceException("不能强接当前调度员自己的振铃分机");
        }
        List<CallLeg> activeLegs = activeLegs(session.getId());
        List<CallLeg> ringingLegs = activeLegs.stream()
            .filter(leg -> targetExtension.equals(leg.getEndpointExtension()))
            .filter(leg -> "AGENT".equals(leg.getLegRole()) || "EXTENSION".equals(leg.getLegRole()))
            .filter(leg -> "RINGING".equals(leg.getLegState()))
            .toList();
        if (ringingLegs.size() != 1) {
            throw new ServiceException(ringingLegs.isEmpty()
                ? "目标分机当前没有可强接的振铃电话腿"
                : "目标分机存在多个振铃电话腿，无法确定强接目标");
        }
        CallLeg ringingLeg = ringingLegs.get(0);
        CallLeg sourceLeg = resolvePickupSourceLeg(activeLegs, ringingLeg);
        EslEndpoint endpoint = endpoint(session.getNodeId());
        if (!commandGateway.callExists(endpoint, ringingLeg.getLegUuid())) {
            throw new ServiceException("目标振铃电话腿已在 FreeSWITCH 中结束");
        }
        if (!commandGateway.callExists(endpoint, sourceLeg.getLegUuid())) {
            throw new ServiceException("原始呼叫电话腿已在 FreeSWITCH 中结束，无法强接");
        }
        if (!SipRegistrationMatcher.isRegistered(commandGateway.listRegisteredExtensions(endpoint),
            supervisor.getExtension(), supervisor.getAuthUsername())) {
            throw new ServiceException("当前调度员分机未在 FreeSWITCH 注册");
        }
        String pickupLegUuid = UUID.randomUUID().toString();
        commandGateway.originatePickup(endpoint, businessCallId, pickupLegUuid, supervisor.getExtension(),
            sourceLeg.getLegUuid(), ringingLeg.getLegUuid(), session.getCallerNumber());
        log.info("调度强接命令已提交，businessCallId={}，sessionId={}，nodeId={}，pickupLegUuid={}，operatorUserId={}，supervisorExtension={}，interceptSourceLegUuid={}，targetRingingLegUuid={}，targetExtension={}",
            businessCallId, session.getId(), session.getNodeId(), pickupLegUuid, supervisor.getUserId(),
            supervisor.getExtension(), sourceLeg.getLegUuid(), ringingLeg.getLegUuid(), targetExtension);
        return pickupLegUuid;
    }

    private enum SupervisionMode {
        MONITOR("监听", "call.monitor.started", "call.monitor.stopped"),
        WHISPER("耳语", "call.whisper.started", "call.whisper.stopped"),
        BARGE("强插", "call.barge.started", "call.barge.stopped");

        private final String label;
        private final String startedEventType;
        private final String stoppedEventType;

        SupervisionMode(String label, String startedEventType, String stoppedEventType) {
            this.label = label;
            this.startedEventType = startedEventType;
            this.stoppedEventType = stoppedEventType;
        }
    }

    private Map<String, Object> supervisionPayload(SupervisionMode mode,
                                                   DispatchOperatorExtensionResponse supervisor,
                                                   String targetExtension) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation", mode.name());
        payload.put("supervisor_agent_id", supervisor.getUserId());
        payload.put("supervisor_extension", supervisor.getExtension());
        payload.put("target_extension", targetExtension);
        return payload;
    }

    private void publishSupervisionEvent(String eventType, CallSession session, Map<String, Object> payload) {
        applicationEventPublisher.publishEvent(new CallSupervisionLifecycleEvent(
            TenantHelper.getTenantId(), eventType, session.getBusinessCallId(), session.getNodeId(),
            LocalDateTime.now(), payload));
    }

    private CallSession requireActiveSession(String businessCallId) {
        CallSession session = sessionMapper.selectOne(new LambdaQueryWrapper<CallSession>()
            .eq(CallSession::getBusinessCallId, businessCallId)
            .isNull(CallSession::getEndedAt)
            .ne(CallSession::getCallStatus, "ENDED")
            .last("limit 1"));
        if (session == null) {
            throw new ServiceException("业务通话不存在或已经结束");
        }
        return session;
    }

    private List<CallLeg> activeLegs(Long sessionId) {
        return legMapper.selectList(new LambdaQueryWrapper<CallLeg>()
            .eq(CallLeg::getSessionId, sessionId)
            .eq(CallLeg::getActive, true)
            .isNull(CallLeg::getEndedAt));
    }

    private int hangupPriority(CallLeg leg) {
        if ("CUSTOMER".equals(leg.getLegRole())) {
            return 0;
        }
        if (isControllableAgentRole(leg.getLegRole())) {
            return 1;
        }
        return 2;
    }

    private boolean isControllableAgentRole(String role) {
        return "AGENT".equals(role) || "CONSULT_AGENT".equals(role) || "PICKUP".equals(role)
            || "DISPATCH_OPERATOR".equals(role) || "DISPATCH_TARGET".equals(role);
    }

    private boolean isControllableEndpointRole(String role) {
        return isControllableAgentRole(role) || "EXTENSION".equals(role);
    }

    private boolean isOperatorExtensionBusy(DispatchOperatorExtensionResponse operator) {
        return legMapper.exists(new LambdaQueryWrapper<CallLeg>()
            .eq(CallLeg::getNodeId, operator.getNodeId())
            .eq(CallLeg::getEndpointExtension, operator.getExtension())
            .eq(CallLeg::getActive, true)
            .isNull(CallLeg::getEndedAt));
    }

    private CallLeg resolvePickupSourceLeg(List<CallLeg> activeLegs, CallLeg ringingLeg) {
        List<CallLeg> customerLegs = activeLegs.stream()
            .filter(leg -> !ringingLeg.getLegUuid().equals(leg.getLegUuid()))
            .filter(leg -> "CUSTOMER".equals(leg.getLegRole()))
            .toList();
        if (customerLegs.size() == 1) {
            return customerLegs.get(0);
        }
        List<CallLeg> dialingLegs = activeLegs.stream()
            .filter(leg -> !ringingLeg.getLegUuid().equals(leg.getLegUuid()))
            .filter(leg -> "DIALING".equals(leg.getLegState()))
            .toList();
        if (dialingLegs.size() == 1) {
            return dialingLegs.get(0);
        }
        throw new ServiceException(customerLegs.size() > 1 || dialingLegs.size() > 1
            ? "原始呼叫存在多个候选电话腿，无法安全执行强接"
            : "未找到原始呼叫电话腿，无法执行强接");
    }

    private void setSatisfactionSkip(EslEndpoint endpoint, String businessCallId, String customerLegUuid) {
        tryCommand(() -> commandGateway.setCallVariable(endpoint, customerLegUuid, "callnexus_satisfaction_skip", "true"),
            "跳过挂机满意度评价", businessCallId);
    }

    private void tryCommand(Runnable command, String action, String businessCallId) {
        try {
            command.run();
        } catch (Exception exception) {
            log.warn("调度通话控制辅助命令失败，不阻断主操作，action={}，businessCallId={}，error={}",
                action, businessCallId, exception.getMessage());
        }
    }

    private void waitForMediaRecovery() {
        try {
            Thread.sleep(TRANSFER_MEDIA_RECOVERY_DELAY_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ServiceException("强制转接被中断");
        }
    }

    private EslEndpoint endpoint(Long nodeId) {
        FreeSwitchNodeConnectionResponse node = nodeQueryService.getEnabledConnection(nodeId);
        return new EslEndpoint(node.getEslHost(), node.getEslPort(), node.getEslPassword(), node.getSipDomain());
    }
}
