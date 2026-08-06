package org.dromara.call.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.call.domain.request.CallNoteRequest;
import org.dromara.call.domain.request.CallConferenceInviteRequest;
import org.dromara.call.domain.request.CallConferenceMuteRequest;
import org.dromara.call.domain.request.IvrTransferRequest;
import org.dromara.call.domain.request.OriginateCallRequest;
import org.dromara.call.domain.request.SendDtmfRequest;
import org.dromara.call.domain.request.TransferCallRequest;
import org.dromara.call.domain.response.CallControlResponse;
import org.dromara.call.domain.response.CallConferenceResponse;
import org.dromara.call.service.CallConferenceApplicationService;
import org.dromara.call.service.CallControlApplicationService;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SaCheckLogin
@RestController
@RequestMapping("/api/v1/calls")
@RequiredArgsConstructor
public class CallControlController {
    private final CallControlApplicationService applicationService;
    private final CallConferenceApplicationService conferenceService;

    @PostMapping
    public R<CallControlResponse> originate(@Valid @RequestBody OriginateCallRequest request) {
        return R.ok(applicationService.originate(request.getDestination()));
    }

    @DeleteMapping("/{callId}")
    public R<Void> hangup(@PathVariable String callId) {
        applicationService.hangup(callId);
        return R.ok();
    }

    @PostMapping("/{callId}/hold")
    public R<Void> hold(@PathVariable String callId) {
        applicationService.hold(callId);
        return R.ok();
    }

    @PostMapping("/{callId}/unhold")
    public R<Void> unhold(@PathVariable String callId) {
        applicationService.unhold(callId);
        return R.ok();
    }

    @PostMapping("/{callId}/mute")
    public R<Void> mute(@PathVariable String callId) {
        applicationService.mute(callId);
        return R.ok();
    }

    @PostMapping("/{callId}/unmute")
    public R<Void> unmute(@PathVariable String callId) {
        applicationService.unmute(callId);
        return R.ok();
    }

    @PostMapping("/{callId}/dtmf")
    public R<Void> sendDtmf(@PathVariable String callId, @Valid @RequestBody SendDtmfRequest request) {
        applicationService.sendDtmf(callId, request.getDigits());
        return R.ok();
    }

    @PostMapping("/{callId}/notes")
    public R<Void> saveNote(@PathVariable String callId, @Valid @RequestBody CallNoteRequest request) {
        applicationService.saveNote(callId, request.getContent());
        return R.ok();
    }

    @PostMapping("/{callId}/transfer")
    public R<Void> blindTransfer(@PathVariable String callId, @Valid @RequestBody TransferCallRequest request) {
        applicationService.blindTransfer(callId, request.getTargetExtension());
        return R.ok();
    }

    @PostMapping("/{callId}/ivr-transfer")
    public R<Void> transferToIvr(@PathVariable String callId, @Valid @RequestBody IvrTransferRequest request) {
        applicationService.transferToIvr(callId, request.getFlowId());
        return R.ok();
    }

    @PostMapping("/{callId}/consult-transfer")
    public R<CallControlResponse> startConsultTransfer(@PathVariable String callId, @Valid @RequestBody TransferCallRequest request) {
        return R.ok(applicationService.startConsultTransfer(callId, request.getTargetExtension(), request.getPhoneMode()));
    }

    @PostMapping("/{callId}/consult-transfer/cancel")
    public R<Void> cancelConsultTransfer(@PathVariable String callId) {
        applicationService.cancelConsultTransfer(callId);
        return R.ok();
    }

    @PostMapping("/{callId}/consult-transfer/complete")
    public R<Void> completeConsultTransfer(@PathVariable String callId) {
        applicationService.completeConsultTransfer(callId);
        return R.ok();
    }

    @PostMapping("/{callId}/consult-transfer/conference")
    public R<CallConferenceResponse> promoteConsultToConference(@PathVariable String callId) {
        return R.ok(conferenceService.promoteConsult(callId));
    }

    @PostMapping("/{callId}/conference")
    public R<CallConferenceResponse> createConference(@PathVariable String callId) {
        return R.ok(conferenceService.create(callId));
    }

    @GetMapping("/{callId}/conference")
    public R<CallConferenceResponse> getConference(@PathVariable String callId) {
        return R.ok(conferenceService.get(callId));
    }

    @PostMapping("/{callId}/conference/invitations")
    public R<CallConferenceResponse> inviteConferenceMember(
        @PathVariable String callId,
        @Valid @RequestBody CallConferenceInviteRequest request) {
        return R.ok(conferenceService.invite(callId, request.getTargetExtension()));
    }

    @PostMapping("/{callId}/conference/members/{memberId}/mute")
    public R<CallConferenceResponse> muteConferenceMember(
        @PathVariable String callId,
        @PathVariable Long memberId,
        @Valid @RequestBody CallConferenceMuteRequest request) {
        return R.ok(conferenceService.muteMember(callId, memberId, Boolean.TRUE.equals(request.getMuted())));
    }

    @DeleteMapping("/{callId}/conference/members/{memberId}")
    public R<CallConferenceResponse> removeConferenceMember(
        @PathVariable String callId,
        @PathVariable Long memberId) {
        return R.ok(conferenceService.removeMember(callId, memberId));
    }

    @PostMapping("/{callId}/conference/leave")
    public R<Void> leaveConference(@PathVariable String callId) {
        conferenceService.leave(callId);
        return R.ok();
    }

    @DeleteMapping("/{callId}/conference")
    public R<Void> endConference(@PathVariable String callId) {
        conferenceService.end(callId);
        return R.ok();
    }
}
