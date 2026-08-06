package org.dromara.openapi.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.call.domain.CallOriginateContext;
import org.dromara.call.domain.response.CallControlResponse;
import org.dromara.call.domain.response.DispatchCallTopologyResponse;
import org.dromara.call.service.CallControlApplicationService;
import org.dromara.call.service.CallConferenceApplicationService;
import org.dromara.call.service.DispatchCallMonitorService;
import org.dromara.call.service.DispatchCallControlService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.openapi.domain.OpenApiApplication;
import org.dromara.openapi.domain.OpenApiRouteGrant;
import org.dromara.openapi.domain.request.OpenApiOriginateCallRequest;
import org.dromara.openapi.domain.request.OpenApiConferenceInviteRequest;
import org.dromara.openapi.domain.request.OpenApiConferenceMuteRequest;
import org.dromara.openapi.domain.request.OpenApiTransferCallRequest;
import org.dromara.openapi.domain.request.OpenApiStandaloneConferenceCreateRequest;
import org.dromara.openapi.domain.request.OpenApiStandaloneConferenceInviteRequest;
import org.dromara.openapi.domain.request.OpenApiSupervisionRequest;
import org.dromara.openapi.domain.response.OpenApiConferenceActionResponse;
import org.dromara.openapi.domain.response.OpenApiConferenceResponse;
import org.dromara.openapi.domain.response.OpenApiConsultCallResponse;
import org.dromara.openapi.domain.response.OpenApiOriginateCallResponse;
import org.dromara.openapi.domain.response.OpenApiSupervisionResponse;
import org.dromara.openapi.mapper.OpenApiApplicationMapper;
import org.dromara.openapi.mapper.OpenApiRouteGrantMapper;
import org.dromara.openapi.security.OpenApiContext;
import org.dromara.openapi.security.OpenApiPrincipal;
import org.redisson.api.RLock;
import org.redisson.api.RSet;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpenApiCallControlService {
    private static final String ACTIVE_CALL_KEY = "callnexus:openapi:application:active-calls:";

    private final CallControlApplicationService callControlService;
    private final CallConferenceApplicationService conferenceService;
    private final DispatchCallMonitorService callMonitorService;
    private final DispatchCallControlService dispatchCallControlService;
    private final OpenApiApplicationMapper applicationMapper;
    private final OpenApiRouteGrantMapper routeGrantMapper;

    public OpenApiOriginateCallResponse originate(OpenApiOriginateCallRequest request) {
        OpenApiPrincipal principal = OpenApiContext.require();
        String key = activeCallKey(principal);
        RLock lock = RedisUtils.getClient().getLock(key + ":lock");
        lock.lock();
        try {
            RSet<String> activeCalls = RedisUtils.getClient().getSet(key);
            removeEndedCalls(activeCalls);
            OpenApiApplication application = requireApplication(principal.applicationId());
            int limit = application.getMaxConcurrentCalls() == null ? 10 : application.getMaxConcurrentCalls();
            if (limit <= 0 || activeCalls.size() >= limit) {
                throw new ServiceException("开放应用通话并发数已达到上限");
            }

            CallOriginateContext context = new CallOriginateContext(null, request.customerId(), null, null,
                request.callerNumberId(), request.skillGroupId(), allowedRoutePolicies(principal.applicationId()));
            CallControlResponse response = callControlService.originate(request.agentId(), request.destination(), context);
            activeCalls.add(response.getBusinessCallId());
            return OpenApiOriginateCallResponse.from(request.agentId(), response);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public void hangup(Long agentId, String businessCallId) {
        callControlService.hangup(agentId, businessCallId);
        RedisUtils.getClient().getSet(activeCallKey(OpenApiContext.require())).remove(businessCallId);
    }

    public void hold(Long agentId, String businessCallId) {
        callControlService.hold(agentId, businessCallId);
    }

    public void unhold(Long agentId, String businessCallId) {
        callControlService.unhold(agentId, businessCallId);
    }

    public void mute(Long agentId, String businessCallId) {
        callControlService.mute(agentId, businessCallId);
    }

    public void unmute(Long agentId, String businessCallId) {
        callControlService.unmute(agentId, businessCallId);
    }

    public void sendDtmf(Long agentId, String businessCallId, String digits) {
        callControlService.sendDtmf(agentId, businessCallId, digits);
    }

    public void blindTransfer(OpenApiTransferCallRequest request, String businessCallId) {
        callControlService.blindTransfer(request.agentId(), businessCallId, request.targetExtension());
    }

    public OpenApiConsultCallResponse startConsultTransfer(OpenApiTransferCallRequest request, String businessCallId) {
        CallControlResponse response = callControlService.startConsultTransfer(request.agentId(), businessCallId,
            request.targetExtension(), request.phoneMode());
        return OpenApiConsultCallResponse.from(request.agentId(), response);
    }

    public void cancelConsultTransfer(Long agentId, String businessCallId) {
        callControlService.cancelConsultTransfer(agentId, businessCallId);
    }

    public void completeConsultTransfer(Long agentId, String businessCallId) {
        callControlService.completeConsultTransfer(agentId, businessCallId);
    }

    public OpenApiConferenceResponse promoteConsultToConference(Long agentId, String businessCallId) {
        return OpenApiConferenceResponse.from(conferenceService.promoteConsult(agentId, businessCallId));
    }

    public OpenApiConferenceResponse createConference(Long agentId, String businessCallId) {
        return OpenApiConferenceResponse.from(conferenceService.create(agentId, businessCallId));
    }

    public OpenApiConferenceResponse getConference(Long agentId, String businessCallId) {
        return OpenApiConferenceResponse.from(conferenceService.get(agentId, businessCallId));
    }

    public OpenApiConferenceResponse inviteConferenceMember(OpenApiConferenceInviteRequest request, String businessCallId) {
        return OpenApiConferenceResponse.from(conferenceService.invite(request.agentId(), businessCallId,
            request.targetExtension()));
    }

    public OpenApiConferenceResponse muteConferenceMember(OpenApiConferenceMuteRequest request, String businessCallId,
                                                           Long memberId) {
        return OpenApiConferenceResponse.from(conferenceService.muteMember(request.agentId(), businessCallId,
            memberId, request.muted()));
    }

    public OpenApiConferenceResponse removeConferenceMember(Long agentId, String businessCallId, Long memberId) {
        return OpenApiConferenceResponse.from(conferenceService.removeMember(agentId, businessCallId, memberId));
    }

    public void leaveConference(Long agentId, String businessCallId) {
        conferenceService.leave(agentId, businessCallId);
    }

    public void endConference(Long agentId, String businessCallId) {
        conferenceService.end(agentId, businessCallId);
    }

    public OpenApiSupervisionResponse startMonitor(OpenApiSupervisionRequest request, String businessCallId) {
        dispatchCallControlService.startMonitor(businessCallId, request.targetExtension(), request.supervisorAgentId());
        return OpenApiSupervisionResponse.accepted(businessCallId, "MONITOR_START",
            request.supervisorAgentId(), request.targetExtension());
    }

    public void stopMonitor(Long supervisorAgentId, String businessCallId) {
        dispatchCallControlService.stopMonitor(businessCallId, supervisorAgentId);
    }

    public OpenApiSupervisionResponse startWhisper(OpenApiSupervisionRequest request, String businessCallId) {
        dispatchCallControlService.startWhisper(businessCallId, request.targetExtension(), request.supervisorAgentId());
        return OpenApiSupervisionResponse.accepted(businessCallId, "WHISPER_START",
            request.supervisorAgentId(), request.targetExtension());
    }

    public void stopWhisper(Long supervisorAgentId, String businessCallId) {
        dispatchCallControlService.stopWhisper(businessCallId, supervisorAgentId);
    }

    public OpenApiSupervisionResponse startBarge(OpenApiSupervisionRequest request, String businessCallId) {
        dispatchCallControlService.startBarge(businessCallId, request.targetExtension(), request.supervisorAgentId());
        return OpenApiSupervisionResponse.accepted(businessCallId, "BARGE_START",
            request.supervisorAgentId(), request.targetExtension());
    }

    public void stopBarge(Long supervisorAgentId, String businessCallId) {
        dispatchCallControlService.stopBarge(businessCallId, supervisorAgentId);
    }

    public void forceHangup(Long supervisorAgentId, String businessCallId) {
        dispatchCallControlService.forceHangup(businessCallId, supervisorAgentId);
        RedisUtils.getClient().getSet(activeCallKey(OpenApiContext.require())).remove(businessCallId);
    }

    public OpenApiConferenceResponse createStandaloneConference(OpenApiStandaloneConferenceCreateRequest request) {
        OpenApiPrincipal principal = OpenApiContext.require();
        String key = activeCallKey(principal);
        RLock lock = RedisUtils.getClient().getLock(key + ":lock");
        lock.lock();
        try {
            RSet<String> activeCalls = RedisUtils.getClient().getSet(key);
            removeEndedCalls(activeCalls);
            OpenApiApplication application = requireApplication(principal.applicationId());
            int limit = application.getMaxConcurrentCalls() == null ? 10 : application.getMaxConcurrentCalls();
            if (limit <= 0 || activeCalls.size() >= limit) {
                throw new ServiceException("开放应用通话并发数已达到上限");
            }
            OpenApiConferenceResponse response = OpenApiConferenceResponse.from(conferenceService.createStandalone(
                request.ownerAgentId(), request.conferenceName(), request.targetExtensions()));
            activeCalls.add(response.businessCallId());
            return response;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public OpenApiConferenceResponse getStandaloneConference(Long agentId, Long conferenceId) {
        return OpenApiConferenceResponse.from(conferenceService.getById(agentId, conferenceId));
    }

    public OpenApiConferenceResponse inviteStandaloneConferenceMembers(
        OpenApiStandaloneConferenceInviteRequest request, Long conferenceId) {
        return OpenApiConferenceResponse.from(conferenceService.inviteById(request.agentId(), conferenceId,
            request.targetExtensions()));
    }

    public OpenApiConferenceResponse muteStandaloneConferenceMember(OpenApiConferenceMuteRequest request,
                                                                    Long conferenceId, Long memberId) {
        return OpenApiConferenceResponse.from(conferenceService.muteMemberById(request.agentId(), conferenceId,
            memberId, request.muted()));
    }

    public OpenApiConferenceResponse removeStandaloneConferenceMember(Long agentId, Long conferenceId, Long memberId) {
        return OpenApiConferenceResponse.from(conferenceService.removeMemberById(agentId, conferenceId, memberId));
    }

    public OpenApiConferenceActionResponse leaveStandaloneConference(Long agentId, Long conferenceId) {
        OpenApiConferenceResponse current = getStandaloneConference(agentId, conferenceId);
        conferenceService.leaveById(agentId, conferenceId);
        return OpenApiConferenceActionResponse.accepted(conferenceId, current.businessCallId(), "CONFERENCE_LEAVE");
    }

    public OpenApiConferenceActionResponse endStandaloneConference(Long agentId, Long conferenceId) {
        OpenApiConferenceResponse current = getStandaloneConference(agentId, conferenceId);
        conferenceService.endById(agentId, conferenceId);
        RedisUtils.getClient().getSet(activeCallKey(OpenApiContext.require())).remove(current.businessCallId());
        return OpenApiConferenceActionResponse.accepted(conferenceId, current.businessCallId(), "CONFERENCE_END");
    }

    private OpenApiApplication requireApplication(Long applicationId) {
        OpenApiApplication application = applicationMapper.selectById(applicationId);
        if (application == null || !Boolean.TRUE.equals(application.getEnabled())) {
            throw new ServiceException("开放应用不存在或已停用");
        }
        return application;
    }

    private Set<String> allowedRoutePolicies(Long applicationId) {
        return new LinkedHashSet<>(routeGrantMapper.selectList(new LambdaQueryWrapper<OpenApiRouteGrant>()
                .eq(OpenApiRouteGrant::getApplicationId, applicationId)
                .eq(OpenApiRouteGrant::getEnabled, true))
            .stream().map(OpenApiRouteGrant::getRoutePolicyCode).toList());
    }

    private void removeEndedCalls(RSet<String> activeCalls) {
        for (String businessCallId : activeCalls.readAll()) {
            try {
                DispatchCallTopologyResponse topology = callMonitorService.getTopology(businessCallId);
                if (topology == null || topology.getCall() == null
                    || "ENDED".equalsIgnoreCase(topology.getCall().getCallStatus())) {
                    activeCalls.remove(businessCallId);
                }
            } catch (RuntimeException exception) {
                activeCalls.remove(businessCallId);
                log.warn("清理开放应用失效通话占用，businessCallId={}，reason={}",
                    businessCallId, exception.getMessage());
            }
        }
    }

    private String activeCallKey(OpenApiPrincipal principal) {
        return ACTIVE_CALL_KEY + principal.tenantId() + ":" + principal.applicationId();
    }
}
