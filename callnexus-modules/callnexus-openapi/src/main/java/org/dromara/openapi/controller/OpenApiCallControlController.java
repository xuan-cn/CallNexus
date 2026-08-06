package org.dromara.openapi.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.openapi.domain.request.OpenApiCallActionRequest;
import org.dromara.openapi.domain.request.OpenApiConferenceInviteRequest;
import org.dromara.openapi.domain.request.OpenApiConferenceMuteRequest;
import org.dromara.openapi.domain.request.OpenApiDtmfRequest;
import org.dromara.openapi.domain.request.OpenApiOriginateCallRequest;
import org.dromara.openapi.domain.request.OpenApiTransferCallRequest;
import org.dromara.openapi.domain.request.OpenApiSupervisionRequest;
import org.dromara.openapi.domain.request.OpenApiSupervisorActionRequest;
import org.dromara.openapi.domain.response.OpenApiCallActionResponse;
import org.dromara.openapi.domain.response.OpenApiConsultCallResponse;
import org.dromara.openapi.domain.response.OpenApiConferenceResponse;
import org.dromara.openapi.domain.response.OpenApiOriginateCallResponse;
import org.dromara.openapi.domain.response.OpenApiSupervisionResponse;
import org.dromara.openapi.security.OpenApiContext;
import org.dromara.openapi.service.OpenApiCallControlService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/openapi/v1/calls")
@RequiredArgsConstructor
public class OpenApiCallControlController {
    private final OpenApiCallControlService callControlService;

    @PostMapping
    public OpenApiOriginateCallResponse originate(@Valid @RequestBody OpenApiOriginateCallRequest request) {
        OpenApiContext.requireScope("call.originate");
        return callControlService.originate(request);
    }

    @PostMapping("/{businessCallId}/hangup")
    public OpenApiCallActionResponse hangup(@PathVariable String businessCallId,
                                            @Valid @RequestBody OpenApiCallActionRequest request) {
        OpenApiContext.requireScope("call.hangup");
        callControlService.hangup(request.agentId(), businessCallId);
        return OpenApiCallActionResponse.accepted(businessCallId, "HANGUP");
    }

    @PostMapping("/{businessCallId}/hold")
    public OpenApiCallActionResponse hold(@PathVariable String businessCallId,
                                          @Valid @RequestBody OpenApiCallActionRequest request) {
        OpenApiContext.requireScope("call.hold");
        callControlService.hold(request.agentId(), businessCallId);
        return OpenApiCallActionResponse.accepted(businessCallId, "HOLD");
    }

    @PostMapping("/{businessCallId}/unhold")
    public OpenApiCallActionResponse unhold(@PathVariable String businessCallId,
                                            @Valid @RequestBody OpenApiCallActionRequest request) {
        OpenApiContext.requireScope("call.hold");
        callControlService.unhold(request.agentId(), businessCallId);
        return OpenApiCallActionResponse.accepted(businessCallId, "UNHOLD");
    }

    @PostMapping("/{businessCallId}/mute")
    public OpenApiCallActionResponse mute(@PathVariable String businessCallId,
                                          @Valid @RequestBody OpenApiCallActionRequest request) {
        OpenApiContext.requireScope("call.mute");
        callControlService.mute(request.agentId(), businessCallId);
        return OpenApiCallActionResponse.accepted(businessCallId, "MUTE");
    }

    @PostMapping("/{businessCallId}/unmute")
    public OpenApiCallActionResponse unmute(@PathVariable String businessCallId,
                                            @Valid @RequestBody OpenApiCallActionRequest request) {
        OpenApiContext.requireScope("call.mute");
        callControlService.unmute(request.agentId(), businessCallId);
        return OpenApiCallActionResponse.accepted(businessCallId, "UNMUTE");
    }

    @PostMapping("/{businessCallId}/dtmf")
    public OpenApiCallActionResponse sendDtmf(@PathVariable String businessCallId,
                                              @Valid @RequestBody OpenApiDtmfRequest request) {
        OpenApiContext.requireScope("call.dtmf");
        callControlService.sendDtmf(request.agentId(), businessCallId, request.digits());
        return OpenApiCallActionResponse.accepted(businessCallId, "DTMF");
    }

    @PostMapping("/{businessCallId}/transfer")
    public OpenApiCallActionResponse blindTransfer(@PathVariable String businessCallId,
                                                   @Valid @RequestBody OpenApiTransferCallRequest request) {
        OpenApiContext.requireScope("call.transfer");
        callControlService.blindTransfer(request, businessCallId);
        return OpenApiCallActionResponse.accepted(businessCallId, "TRANSFER");
    }

    @PostMapping("/{businessCallId}/consult-transfer")
    public OpenApiConsultCallResponse startConsultTransfer(@PathVariable String businessCallId,
                                                           @Valid @RequestBody OpenApiTransferCallRequest request) {
        OpenApiContext.requireScope("call.consult");
        return callControlService.startConsultTransfer(request, businessCallId);
    }

    @PostMapping("/{businessCallId}/consult-transfer/cancel")
    public OpenApiCallActionResponse cancelConsultTransfer(@PathVariable String businessCallId,
                                                           @Valid @RequestBody OpenApiCallActionRequest request) {
        OpenApiContext.requireScope("call.consult");
        callControlService.cancelConsultTransfer(request.agentId(), businessCallId);
        return OpenApiCallActionResponse.accepted(businessCallId, "CONSULT_CANCEL");
    }

    @PostMapping("/{businessCallId}/consult-transfer/complete")
    public OpenApiCallActionResponse completeConsultTransfer(@PathVariable String businessCallId,
                                                             @Valid @RequestBody OpenApiCallActionRequest request) {
        OpenApiContext.requireScope("call.consult");
        callControlService.completeConsultTransfer(request.agentId(), businessCallId);
        return OpenApiCallActionResponse.accepted(businessCallId, "CONSULT_COMPLETE");
    }

    @PostMapping("/{businessCallId}/consult-transfer/conference")
    public OpenApiConferenceResponse promoteConsultToConference(@PathVariable String businessCallId,
                                                                @Valid @RequestBody OpenApiCallActionRequest request) {
        OpenApiContext.requireScope("call.conference");
        return callControlService.promoteConsultToConference(request.agentId(), businessCallId);
    }

    @PostMapping("/{businessCallId}/conference")
    public OpenApiConferenceResponse createConference(@PathVariable String businessCallId,
                                                      @Valid @RequestBody OpenApiCallActionRequest request) {
        OpenApiContext.requireScope("call.conference");
        return callControlService.createConference(request.agentId(), businessCallId);
    }

    @GetMapping("/{businessCallId}/conference")
    public OpenApiConferenceResponse getConference(@PathVariable String businessCallId,
                                                   @RequestParam("agent_id") Long agentId) {
        OpenApiContext.requireScope("call.conference");
        return callControlService.getConference(agentId, businessCallId);
    }

    @PostMapping("/{businessCallId}/conference/invitations")
    public OpenApiConferenceResponse inviteConferenceMember(@PathVariable String businessCallId,
                                                            @Valid @RequestBody OpenApiConferenceInviteRequest request) {
        OpenApiContext.requireScope("call.conference");
        return callControlService.inviteConferenceMember(request, businessCallId);
    }

    @PostMapping("/{businessCallId}/conference/members/{memberId}/mute")
    public OpenApiConferenceResponse muteConferenceMember(@PathVariable String businessCallId,
                                                          @PathVariable Long memberId,
                                                          @Valid @RequestBody OpenApiConferenceMuteRequest request) {
        OpenApiContext.requireScope("call.conference");
        return callControlService.muteConferenceMember(request, businessCallId, memberId);
    }

    @DeleteMapping("/{businessCallId}/conference/members/{memberId}")
    public OpenApiConferenceResponse removeConferenceMember(@PathVariable String businessCallId,
                                                            @PathVariable Long memberId,
                                                            @RequestParam("agent_id") Long agentId) {
        OpenApiContext.requireScope("call.conference");
        return callControlService.removeConferenceMember(agentId, businessCallId, memberId);
    }

    @PostMapping("/{businessCallId}/conference/leave")
    public OpenApiCallActionResponse leaveConference(@PathVariable String businessCallId,
                                                     @Valid @RequestBody OpenApiCallActionRequest request) {
        OpenApiContext.requireScope("call.conference");
        callControlService.leaveConference(request.agentId(), businessCallId);
        return OpenApiCallActionResponse.accepted(businessCallId, "CONFERENCE_LEAVE");
    }

    @DeleteMapping("/{businessCallId}/conference")
    public OpenApiCallActionResponse endConference(@PathVariable String businessCallId,
                                                   @RequestParam("agent_id") Long agentId) {
        OpenApiContext.requireScope("call.conference");
        callControlService.endConference(agentId, businessCallId);
        return OpenApiCallActionResponse.accepted(businessCallId, "CONFERENCE_END");
    }

    @PostMapping("/{businessCallId}/monitor")
    public OpenApiSupervisionResponse startMonitor(@PathVariable String businessCallId,
                                                   @Valid @RequestBody OpenApiSupervisionRequest request) {
        OpenApiContext.requireScope("dispatch.monitor");
        return callControlService.startMonitor(request, businessCallId);
    }

    @DeleteMapping("/{businessCallId}/monitor")
    public OpenApiCallActionResponse stopMonitor(@PathVariable String businessCallId,
                                                 @RequestParam("supervisor_agent_id") Long supervisorAgentId) {
        OpenApiContext.requireScope("dispatch.monitor");
        callControlService.stopMonitor(supervisorAgentId, businessCallId);
        return OpenApiCallActionResponse.accepted(businessCallId, "MONITOR_STOP");
    }

    @PostMapping("/{businessCallId}/whisper")
    public OpenApiSupervisionResponse startWhisper(@PathVariable String businessCallId,
                                                   @Valid @RequestBody OpenApiSupervisionRequest request) {
        OpenApiContext.requireScope("dispatch.whisper");
        return callControlService.startWhisper(request, businessCallId);
    }

    @DeleteMapping("/{businessCallId}/whisper")
    public OpenApiCallActionResponse stopWhisper(@PathVariable String businessCallId,
                                                 @RequestParam("supervisor_agent_id") Long supervisorAgentId) {
        OpenApiContext.requireScope("dispatch.whisper");
        callControlService.stopWhisper(supervisorAgentId, businessCallId);
        return OpenApiCallActionResponse.accepted(businessCallId, "WHISPER_STOP");
    }

    @PostMapping("/{businessCallId}/barge")
    public OpenApiSupervisionResponse startBarge(@PathVariable String businessCallId,
                                                 @Valid @RequestBody OpenApiSupervisionRequest request) {
        OpenApiContext.requireScope("dispatch.barge");
        return callControlService.startBarge(request, businessCallId);
    }

    @DeleteMapping("/{businessCallId}/barge")
    public OpenApiCallActionResponse stopBarge(@PathVariable String businessCallId,
                                               @RequestParam("supervisor_agent_id") Long supervisorAgentId) {
        OpenApiContext.requireScope("dispatch.barge");
        callControlService.stopBarge(supervisorAgentId, businessCallId);
        return OpenApiCallActionResponse.accepted(businessCallId, "BARGE_STOP");
    }

    @PostMapping("/{businessCallId}/force-hangup")
    public OpenApiCallActionResponse forceHangup(@PathVariable String businessCallId,
                                                 @Valid @RequestBody OpenApiSupervisorActionRequest request) {
        OpenApiContext.requireScope("dispatch.force_hangup");
        callControlService.forceHangup(request.supervisorAgentId(), businessCallId);
        return OpenApiCallActionResponse.accepted(businessCallId, "FORCE_HANGUP");
    }
}
