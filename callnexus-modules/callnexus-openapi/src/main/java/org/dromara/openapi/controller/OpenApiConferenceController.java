package org.dromara.openapi.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.openapi.domain.request.OpenApiConferenceMuteRequest;
import org.dromara.openapi.domain.request.OpenApiStandaloneConferenceCreateRequest;
import org.dromara.openapi.domain.request.OpenApiStandaloneConferenceInviteRequest;
import org.dromara.openapi.domain.response.OpenApiConferenceActionResponse;
import org.dromara.openapi.domain.response.OpenApiConferenceResponse;
import org.dromara.openapi.security.OpenApiContext;
import org.dromara.openapi.service.OpenApiCallControlService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/openapi/v1/conferences")
@RequiredArgsConstructor
public class OpenApiConferenceController {
    private final OpenApiCallControlService callControlService;

    @PostMapping
    public OpenApiConferenceResponse create(@Valid @RequestBody OpenApiStandaloneConferenceCreateRequest request) {
        OpenApiContext.requireScope("call.conference");
        return callControlService.createStandaloneConference(request);
    }

    @GetMapping("/{conferenceId}")
    public OpenApiConferenceResponse get(@PathVariable Long conferenceId,
                                         @RequestParam("agent_id") Long agentId) {
        OpenApiContext.requireScope("call.conference");
        return callControlService.getStandaloneConference(agentId, conferenceId);
    }

    @PostMapping("/{conferenceId}/invitations")
    public OpenApiConferenceResponse invite(@PathVariable Long conferenceId,
                                            @Valid @RequestBody OpenApiStandaloneConferenceInviteRequest request) {
        OpenApiContext.requireScope("call.conference");
        return callControlService.inviteStandaloneConferenceMembers(request, conferenceId);
    }

    @PostMapping("/{conferenceId}/members/{memberId}/mute")
    public OpenApiConferenceResponse mute(@PathVariable Long conferenceId,
                                          @PathVariable Long memberId,
                                          @Valid @RequestBody OpenApiConferenceMuteRequest request) {
        OpenApiContext.requireScope("call.conference");
        return callControlService.muteStandaloneConferenceMember(request, conferenceId, memberId);
    }

    @DeleteMapping("/{conferenceId}/members/{memberId}")
    public OpenApiConferenceResponse remove(@PathVariable Long conferenceId,
                                            @PathVariable Long memberId,
                                            @RequestParam("agent_id") Long agentId) {
        OpenApiContext.requireScope("call.conference");
        return callControlService.removeStandaloneConferenceMember(agentId, conferenceId, memberId);
    }

    @PostMapping("/{conferenceId}/leave")
    public OpenApiConferenceActionResponse leave(@PathVariable Long conferenceId,
                                                 @RequestParam("agent_id") Long agentId) {
        OpenApiContext.requireScope("call.conference");
        return callControlService.leaveStandaloneConference(agentId, conferenceId);
    }

    @DeleteMapping("/{conferenceId}")
    public OpenApiConferenceActionResponse end(@PathVariable Long conferenceId,
                                               @RequestParam("agent_id") Long agentId) {
        OpenApiContext.requireScope("call.conference");
        return callControlService.endStandaloneConference(agentId, conferenceId);
    }
}
