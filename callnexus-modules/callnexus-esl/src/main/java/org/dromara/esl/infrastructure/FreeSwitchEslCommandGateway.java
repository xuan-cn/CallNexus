package org.dromara.esl.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.dromara.call.domain.EslEndpoint;
import org.dromara.call.domain.OutboundRoute;
import org.dromara.call.domain.CallOriginateContext;
import org.dromara.call.service.TelephonyCommandGateway;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.resource.gateway.support.OutboundGatewayDialString;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class FreeSwitchEslCommandGateway implements TelephonyCommandGateway {
    private static final int CONNECT_TIMEOUT_MILLIS = 5000;
    private static final int READ_TIMEOUT_MILLIS = 5000;
    private static final int OUTBOUND_RING_TIMEOUT_SECONDS = 60;
    private static final String OUTBOUND_GATEWAY_CODEC = "PCMA";
    private static final String INTERNAL_ENDPOINT_CODEC = "PCMA";
    private static final Pattern REGISTERED_USER_PATTERN = Pattern.compile("(?m)^User:\\s*([^@\\s]+)@.+$");
    private static final Pattern REGISTERED_AUTH_USER_PATTERN = Pattern.compile("(?m)^Auth-User:\\s*([^\\s]+)\\s*$");

    @Override
    public void originate(EslEndpoint endpoint, String callId, String agentExtension, String destination,
                          OutboundRoute outboundRoute, CallOriginateContext context) {
        requireDialValue(agentExtension);
        requireDialValue(destination);
        requireDialValue(endpoint.sipDomain());
        String callerIdNumber = outboundRoute != null && outboundRoute.isExternal() ? outboundRoute.getCallerIdNumber() : agentExtension;
        requireDialValue(callerIdNumber);
        CallOriginateContext safeContext = context == null ? CallOriginateContext.empty() : context;
        String businessCallId = businessCallId(callId, context);
        String variables = "{origination_uuid=" + callId
            + ",callnexus_business_call_id=" + businessCallId
            + ",callnexus_direction=" + (outboundRoute != null && outboundRoute.isExternal() ? "OUTBOUND" : "INTERNAL")
            + (outboundRoute == null || !outboundRoute.isExternal() ? ",media_mix_inbound_outbound_codecs=true" : "")
            + ",callnexus_original_caller=" + agentExtension
            + ",callnexus_original_called=" + destination
            + optionalVariable("callnexus_customer_id", safeContext.customerId())
            + optionalVariable("callnexus_outbound_task_id", safeContext.outboundTaskId())
            + optionalVariable("callnexus_outbound_member_id", safeContext.outboundMemberId())
            + ",origination_caller_id_number=" + callerIdNumber
            + ",origination_caller_id_name=" + callerIdNumber
            + ",execute_on_answer=record_session::/var/lib/freeswitch/recordings/" + callId + ".wav"
            + ",api_hangup_hook='bg_system /opt/callnexus/bin/upload-recording.sh " + businessCallId
            + " /var/lib/freeswitch/recordings/" + callId + ".wav'"
            + ",hangup_after_bridge=true}";
        String destinationDialString = destinationDialString(endpoint, destination, endpoint.sipDomain(), outboundRoute,
            businessCallId, agentExtension);
        String command = "bgapi originate " + variables + userDialString(agentExtension, endpoint.sipDomain())
            + " &bridge(" + destinationDialString + ")";
        sendCommand(endpoint, command);
        log.info("FreeSWITCH 发起呼叫命令已提交，channelUuid={}，businessCallId={}，agentExtension={}，destination={}，external={}，gatewayCode={}，callerIdNumber={}，customerId={}，outboundTaskId={}，outboundMemberId={}",
            callId, businessCallId, agentExtension, destination, outboundRoute != null && outboundRoute.isExternal(),
            outboundRoute == null ? null : outboundRoute.getGatewayCode(), callerIdNumber,
            safeContext.customerId(), safeContext.outboundTaskId(), safeContext.outboundMemberId());
    }

    @Override
    public void originateAgentless(EslEndpoint endpoint, String callId, String destination, OutboundRoute outboundRoute,
                                   CallOriginateContext context, String answeredDestination,
                                   Map<String, String> channelVariables) {
        requireDialValue(callId);
        requireDialValue(destination);
        requireDialValue(answeredDestination);
        if (outboundRoute == null || !outboundRoute.isExternal()) {
            throw new ServiceException("自动外呼当前只支持外线号码");
        }
        CallOriginateContext safeContext = context == null ? CallOriginateContext.empty() : context;
        String businessCallId = businessCallId(callId, safeContext);
        String callerIdNumber = outboundRoute.getCallerIdNumber();
        requireDialValue(callerIdNumber);
        StringBuilder variables = new StringBuilder("{origination_uuid=").append(callId)
            .append(",callnexus_business_call_id=").append(businessCallId)
            .append(",callnexus_direction=OUTBOUND")
            .append(",callnexus_original_caller=").append(callerIdNumber)
            .append(",callnexus_original_called=").append(destination)
            .append(optionalVariable("callnexus_customer_id", safeContext.customerId()))
            .append(optionalVariable("callnexus_outbound_task_id", safeContext.outboundTaskId()))
            .append(optionalVariable("callnexus_outbound_member_id", safeContext.outboundMemberId()))
            .append(",RECORD_STEREO=true,record_sample_rate=8000")
            .append(",origination_caller_id_number=").append(callerIdNumber)
            .append(",origination_caller_id_name=").append(callerIdNumber)
            .append(",execute_on_answer=record_session::/var/lib/freeswitch/recordings/").append(callId).append(".wav")
            .append(",api_hangup_hook='bg_system /opt/callnexus/bin/upload-recording.sh ").append(businessCallId)
            .append(" /var/lib/freeswitch/recordings/").append(callId).append(".wav'")
            .append(",hangup_after_bridge=true");
        if (channelVariables != null) {
            channelVariables.forEach((name, value) -> variables.append(',').append(name).append('=').append(value));
        }
        variables.append('}');
        String dialString = destinationDialString(endpoint, destination, endpoint.sipDomain(), outboundRoute,
            businessCallId, callerIdNumber);
        String command = "bgapi originate " + variables + dialString
            + " &transfer(" + answeredDestination + " XML default)";
        sendCommand(endpoint, command);
        log.info("FreeSWITCH 无人值守外呼命令已提交，channelUuid={}，businessCallId={}，destination={}，target={}，taskId={}，memberId={}",
            callId, businessCallId, destination, answeredDestination,
            safeContext.outboundTaskId(), safeContext.outboundMemberId());
    }

    @Override
    public void hangup(EslEndpoint endpoint, String callId) {
        requireCallId(callId);
        sendCommand(endpoint, "api uuid_kill " + callId + " NORMAL_CLEARING");
        log.info("FreeSWITCH hangup command accepted, callId={}", callId);
    }

    @Override
    public void hold(EslEndpoint endpoint, String callId) {
        requireCallId(callId);
        sendCommand(endpoint, "api uuid_hold " + callId);
        log.info("FreeSWITCH 通话保持命令已提交，callId={}", callId);
    }

    @Override
    public void unhold(EslEndpoint endpoint, String callId) {
        requireCallId(callId);
        sendCommand(endpoint, "api uuid_hold off " + callId);
        log.info("FreeSWITCH 取消保持命令已提交，callId={}", callId);
    }

    @Override
    public void mute(EslEndpoint endpoint, String callId) {
        requireCallId(callId);
        sendCommand(endpoint, "api uuid_audio " + callId + " start read mute -4");
        log.info("FreeSWITCH 坐席腿静音命令已提交，callId={}", callId);
    }

    @Override
    public void unmute(EslEndpoint endpoint, String callId) {
        requireCallId(callId);
        sendCommand(endpoint, "api uuid_audio " + callId + " stop");
        log.info("FreeSWITCH 坐席腿取消静音命令已提交，callId={}", callId);
    }

    @Override
    public void sendDtmf(EslEndpoint endpoint, String callId, String digits) {
        requireCallId(callId);
        requireDtmfDigits(digits);
        String safeDigits = digits.toUpperCase();
        sendCommand(endpoint, "api uuid_send_dtmf " + callId + " " + safeDigits);
        log.info("FreeSWITCH DTMF 命令已提交，callId={}，digits={}", callId, safeDigits);
    }

    @Override
    public void broadcastPlayback(EslEndpoint endpoint, String callId, String mediaPath, String leg) {
        requireCallId(callId);
        requireBroadcastMediaPath(mediaPath);
        String safeLeg = requireBroadcastLeg(leg);
        sendCommand(endpoint, "api uuid_broadcast " + callId + " " + mediaPath + " " + safeLeg);
        log.info("FreeSWITCH 指定通话腿播放媒体命令已提交，callId={}，leg={}，mediaPath={}", callId, safeLeg, mediaPath);
    }

    @Override
    public void broadcastSayNumber(EslEndpoint endpoint, String callId, String language, String number, String leg) {
        requireCallId(callId);
        requireDialValue(language);
        requireDialValue(number);
        String safeLeg = requireBroadcastLeg(leg);
        String safeNumber = number.replaceAll("[^0-9]", "");
        if (safeNumber.isBlank()) {
            throw new ServiceException("FREESWITCH_BROADCAST_NUMBER_INVALID");
        }
        sendCommand(endpoint, "api uuid_broadcast " + callId + " say::" + language + "\\snumber\\siterated\\s" + safeNumber + " " + safeLeg);
        log.info("FreeSWITCH 指定通话腿报号码命令已提交，callId={}，leg={}，language={}，number={}", callId, safeLeg, language, safeNumber);
    }

    @Override
    public void park(EslEndpoint endpoint, String callId) {
        requireCallId(callId);
        sendCommand(endpoint, "api uuid_park " + callId);
        log.info("FreeSWITCH 通话驻留命令已提交，callId={}", callId);
    }

    @Override
    public void recoverMedia(EslEndpoint endpoint, String callId) {
        requireCallId(callId);
        EslFrame response = executeCommand(endpoint, "api uuid_media " + callId);
        String reply = response.header("Reply-Text");
        if (reply == null || reply.isBlank()) {
            reply = response.body();
        }
        if (isSuccessResponse(reply)) {
            log.info("FreeSWITCH 通话媒体恢复命令已提交，callId={}，response={}", callId, reply);
            return;
        }
        log.warn("FreeSWITCH 通话媒体恢复命令失败，继续后续流程，callId={}，response={}", callId, reply);
    }

    @Override
    public boolean callsAreBridged(EslEndpoint endpoint, String leftCallId, String rightCallId) {
        requireCallId(leftCallId);
        requireCallId(rightCallId);
        EslFrame response = executeCommand(endpoint, "api uuid_getvar " + leftCallId + " bridge_uuid");
        String bridgeUuid = response.body();
        if (bridgeUuid == null || bridgeUuid.isBlank()) {
            bridgeUuid = response.header("Reply-Text");
        }
        return rightCallId.equals(bridgeUuid == null ? null : bridgeUuid.trim());
    }

    @Override
    public void setCallVariable(EslEndpoint endpoint, String callId, String name, String value) {
        requireCallId(callId);
        requireDialValue(name);
        requireDialValue(value);
        sendCommand(endpoint, "api uuid_setvar " + callId + " " + name + " " + value);
        log.info("FreeSWITCH 通话变量已设置，callId={}，name={}，value={}", callId, name, value);
    }

    @Override
    public void startSpeechRecognition(EslEndpoint endpoint, String callId, String profile, String grammar, String detectScript) {
        requireCallId(callId);
        requireSafeSpeechValue(profile, "profile");
        requireSafeSpeechValue(grammar, "grammar");
        requireSafeSpeechValue(detectScript, "detectScript");
        sendCommand(endpoint, "api uuid_setvar " + callId + " fire_asr_events true");
        sendCommand(endpoint, "api uuid_setvar " + callId + " callnexus_unimrcp_profile " + profile);
        sendCommand(endpoint, "api uuid_setvar " + callId + " callnexus_unimrcp_grammar " + grammar);
        sendCommand(endpoint, "api luarun " + detectScript + " " + callId);
        log.info("FreeSWITCH speech recognition command accepted, callId={}, profile={}", callId, profile);
    }

    @Override
    public void stopSpeechRecognition(EslEndpoint endpoint, String callId) {
        requireCallId(callId);
        sendCommand(endpoint, "api uuid_detect_speech " + callId + " stop");
        log.info("FreeSWITCH speech recognition stop command accepted, callId={}", callId);
    }

    @Override
    public void bridgeCalls(EslEndpoint endpoint, String leftCallId, String rightCallId) {
        requireCallId(leftCallId);
        requireCallId(rightCallId);
        sendCommand(endpoint, "api uuid_bridge " + leftCallId + " " + rightCallId);
        log.info("FreeSWITCH 双腿桥接命令已提交，leftCallId={}，rightCallId={}", leftCallId, rightCallId);
    }

    @Override
    public void blindTransfer(EslEndpoint endpoint, String callId, String targetExtension) {
        requireCallId(callId);
        requireDialValue(targetExtension);
        requireDialValue(endpoint.sipDomain());
        sendCommand(endpoint, "api uuid_transfer " + callId + " " + targetExtension + " XML default");
        log.info("FreeSWITCH 盲转客户通话腿命令已提交，callId={}，targetExtension={}", callId, targetExtension);
    }

    @Override
    public void originateConsultation(EslEndpoint endpoint, String businessCallId, String consultCallId, String agentExtension,
                                      String targetExtension, String customerLegUuid, String sourceAgentLegUuid) {
        requireDialValue(businessCallId);
        requireCallId(consultCallId);
        requireCallId(customerLegUuid);
        requireCallId(sourceAgentLegUuid);
        requireDialValue(agentExtension);
        requireDialValue(targetExtension);
        requireDialValue(endpoint.sipDomain());
        String variables = "{origination_uuid=" + consultCallId
            + ",callnexus_business_call_id=" + businessCallId
            + ",callnexus_direction=INTERNAL"
            + ",callnexus_call_purpose=CONSULT"
            + ",callnexus_original_call_id=" + customerLegUuid
            + ",callnexus_consult_call_id=" + consultCallId
            + ",callnexus_customer_leg_uuid=" + customerLegUuid
            + ",callnexus_source_agent_leg_uuid=" + sourceAgentLegUuid
            + ",callnexus_consult_leg_uuid=" + consultCallId
            + ",callnexus_source_agent_extension=" + agentExtension
            + ",callnexus_target_agent_extension=" + targetExtension
            + ",callnexus_original_caller=" + agentExtension
            + ",callnexus_original_called=" + targetExtension
            + ",origination_caller_id_number=" + agentExtension
            + ",origination_caller_id_name=" + agentExtension
            + ",hangup_after_bridge=false"
            + ",park_after_bridge=true}";
        String command = "bgapi originate " + variables + internalEndpointDialString(targetExtension, endpoint.sipDomain())
            + " &park()";
        sendCommand(endpoint, command);
        log.info("FreeSWITCH 咨询呼叫命令已提交，businessCallId={}，consultCallId={}，agentExtension={}，targetExtension={}",
            businessCallId, consultCallId, agentExtension, targetExtension);
    }

    @Override
    public boolean callExists(EslEndpoint endpoint, String callId) {
        requireCallId(callId);
        EslFrame response = executeCommand(endpoint, "api uuid_exists " + callId);
        return "true".equalsIgnoreCase(response.body().trim());
    }

    @Override
    public Set<String> listRegisteredExtensions(EslEndpoint endpoint) {
        EslFrame response = executeCommand(endpoint, "api sofia status profile internal reg");
        Set<String> extensions = new LinkedHashSet<>();
        Matcher matcher = REGISTERED_USER_PATTERN.matcher(response.body());
        while (matcher.find()) {
            extensions.add(matcher.group(1));
        }
        Matcher authMatcher = REGISTERED_AUTH_USER_PATTERN.matcher(response.body());
        while (authMatcher.find()) {
            extensions.add(authMatcher.group(1));
        }
        return extensions;
    }

    @Override
    public void originateMonitor(EslEndpoint endpoint, String businessCallId, String monitorLegUuid,
                                 String supervisorExtension, String targetAgentLegUuid, String targetExtension) {
        requireDialValue(businessCallId);
        requireCallId(monitorLegUuid);
        requireCallId(targetAgentLegUuid);
        requireDialValue(supervisorExtension);
        requireDialValue(targetExtension);
        requireDialValue(endpoint.sipDomain());
        String variables = "{origination_uuid=" + monitorLegUuid
            + ",callnexus_business_call_id=" + businessCallId
            + ",callnexus_direction=INTERNAL"
            + ",callnexus_call_purpose=DISPATCH_MONITOR"
            + ",callnexus_monitor_target_leg_uuid=" + targetAgentLegUuid
            + ",callnexus_original_caller=" + supervisorExtension
            + ",callnexus_original_called=" + targetExtension
            + ",eavesdrop_enable_dtmf=true"
            + ",origination_caller_id_number=" + targetExtension
            + ",origination_caller_id_name=调度监听"
            + ",hangup_after_bridge=true}";
        String command = "bgapi originate " + variables + userDialString(supervisorExtension, endpoint.sipDomain())
            + " &eavesdrop(" + targetAgentLegUuid + ")";
        sendCommand(endpoint, command);
        log.info("FreeSWITCH 调度监听呼叫已提交，businessCallId={}，monitorLegUuid={}，supervisorExtension={}，targetAgentLegUuid={}，targetExtension={}",
            businessCallId, monitorLegUuid, supervisorExtension, targetAgentLegUuid, targetExtension);
    }

    @Override
    public void originateWhisper(EslEndpoint endpoint, String businessCallId, String whisperLegUuid,
                                 String supervisorExtension, String targetAgentLegUuid, String targetExtension) {
        requireDialValue(businessCallId);
        requireCallId(whisperLegUuid);
        requireCallId(targetAgentLegUuid);
        requireDialValue(supervisorExtension);
        requireDialValue(targetExtension);
        requireDialValue(endpoint.sipDomain());
        String variables = "{origination_uuid=" + whisperLegUuid
            + ",callnexus_business_call_id=" + businessCallId
            + ",callnexus_direction=INTERNAL"
            + ",callnexus_call_purpose=DISPATCH_WHISPER"
            + ",callnexus_monitor_target_leg_uuid=" + targetAgentLegUuid
            + ",callnexus_original_caller=" + supervisorExtension
            + ",callnexus_original_called=" + targetExtension
            + ",eavesdrop_enable_dtmf=true"
            + ",origination_caller_id_number=" + targetExtension
            + ",origination_caller_id_name=调度耳语"
            + ",hangup_after_bridge=true}";
        String command = "bgapi originate " + variables + userDialString(supervisorExtension, endpoint.sipDomain())
            + " 'queue_dtmf:w2@500,eavesdrop:" + targetAgentLegUuid + "' inline";
        sendCommand(endpoint, command);
        log.info("FreeSWITCH 调度耳语呼叫已提交，businessCallId={}，whisperLegUuid={}，supervisorExtension={}，targetAgentLegUuid={}，targetExtension={}",
            businessCallId, whisperLegUuid, supervisorExtension, targetAgentLegUuid, targetExtension);
    }

    @Override
    public void originateBarge(EslEndpoint endpoint, String businessCallId, String bargeLegUuid,
                               String supervisorExtension, String targetAgentLegUuid, String targetExtension) {
        requireDialValue(businessCallId);
        requireCallId(bargeLegUuid);
        requireCallId(targetAgentLegUuid);
        requireDialValue(supervisorExtension);
        requireDialValue(targetExtension);
        requireDialValue(endpoint.sipDomain());
        String variables = "{origination_uuid=" + bargeLegUuid
            + ",callnexus_business_call_id=" + businessCallId
            + ",callnexus_direction=INTERNAL"
            + ",callnexus_call_purpose=DISPATCH_BARGE"
            + ",callnexus_monitor_target_leg_uuid=" + targetAgentLegUuid
            + ",callnexus_original_caller=" + supervisorExtension
            + ",callnexus_original_called=" + targetExtension
            + ",eavesdrop_enable_dtmf=true"
            + ",origination_caller_id_number=" + targetExtension
            + ",origination_caller_id_name=调度强插"
            + ",hangup_after_bridge=true}";
        String command = "bgapi originate " + variables + userDialString(supervisorExtension, endpoint.sipDomain())
            + " 'queue_dtmf:w3@500,eavesdrop:" + targetAgentLegUuid + "' inline";
        sendCommand(endpoint, command);
        log.info("FreeSWITCH 调度强插呼叫已提交，businessCallId={}，bargeLegUuid={}，supervisorExtension={}，targetAgentLegUuid={}，targetExtension={}",
            businessCallId, bargeLegUuid, supervisorExtension, targetAgentLegUuid, targetExtension);
    }

    @Override
    public void originatePickup(EslEndpoint endpoint, String businessCallId, String pickupLegUuid,
                                String supervisorExtension, String interceptSourceLegUuid,
                                String targetRingingLegUuid, String callerNumber) {
        requireDialValue(businessCallId);
        requireCallId(pickupLegUuid);
        requireCallId(interceptSourceLegUuid);
        requireCallId(targetRingingLegUuid);
        requireDialValue(supervisorExtension);
        requireDialValue(endpoint.sipDomain());
        String safeCallerNumber = callerNumber != null && callerNumber.matches("^[A-Za-z0-9._*#+-]{1,128}$")
            ? callerNumber : "anonymous";
        String variables = "{origination_uuid=" + pickupLegUuid
            + ",callnexus_business_call_id=" + businessCallId
            + ",callnexus_direction=INBOUND"
            + ",callnexus_call_purpose=DISPATCH_PICKUP"
            + ",callnexus_monitor_target_leg_uuid=" + targetRingingLegUuid
            + ",callnexus_original_caller=" + safeCallerNumber
            + ",callnexus_original_called=" + supervisorExtension
            + ",intercept_unanswered_only=true"
            + ",origination_caller_id_number=" + safeCallerNumber
            + ",origination_caller_id_name=" + safeCallerNumber
            + ",hangup_after_bridge=true}";
        String command = "bgapi originate " + variables + userDialString(supervisorExtension, endpoint.sipDomain())
            + " &intercept(" + interceptSourceLegUuid + ")";
        sendCommand(endpoint, command);
        log.info("FreeSWITCH 调度强接呼叫已提交，businessCallId={}，pickupLegUuid={}，supervisorExtension={}，interceptSourceLegUuid={}，targetRingingLegUuid={}，callerNumber={}",
            businessCallId, pickupLegUuid, supervisorExtension, interceptSourceLegUuid, targetRingingLegUuid, safeCallerNumber);
    }

    @Override
    public void originateDispatchParticipant(EslEndpoint endpoint, String businessCallId, String participantLegUuid,
                                             String conferenceName, String extension, String callerIdNumber,
                                             String purpose, Long dispatchTaskId, Long dispatchTargetId) {
        requireDialValue(businessCallId);
        requireCallId(participantLegUuid);
        requireDialValue(conferenceName);
        requireDialValue(extension);
        requireDialValue(callerIdNumber);
        requireDialValue(purpose);
        requireDialValue(endpoint.sipDomain());
        if (dispatchTaskId == null) {
            throw new ServiceException("调度呼叫任务ID不能为空");
        }
        String variables = "{origination_uuid=" + participantLegUuid
            + ",callnexus_business_call_id=" + businessCallId
            + ",callnexus_direction=INTERNAL"
            + ",callnexus_call_purpose=" + purpose
            + ",callnexus_dispatch_task_id=" + dispatchTaskId
            + optionalVariable("callnexus_dispatch_target_id", dispatchTargetId)
            + ",callnexus_original_caller=" + callerIdNumber
            + ",callnexus_original_called=" + extension
            + ",origination_caller_id_number=" + callerIdNumber
            + ",origination_caller_id_name=" + callerIdNumber
            + intercomAutoAnswerVariables(purpose)
            + ",hangup_after_bridge=true}";
        String conferenceArguments = conferenceName + "@default";
        if ("DISPATCH_CALL_OPERATOR".equals(purpose) || "DISPATCH_INTERCOM_OPERATOR".equals(purpose)) {
            conferenceArguments += "+flags{moderator|endconf}";
        }
        String command = "bgapi originate " + variables + userDialString(extension, endpoint.sipDomain())
            + " &conference(" + conferenceArguments + ")";
        sendCommand(endpoint, command);
        log.info("FreeSWITCH 调度呼叫参与方命令已提交，businessCallId={}，dispatchTaskId={}，dispatchTargetId={}，purpose={}，participantLegUuid={}，extension={}，conferenceName={}",
            businessCallId, dispatchTaskId, dispatchTargetId, purpose, participantLegUuid, extension,
            conferenceName);
    }

    @Override
    public void promoteBridgeToConference(EslEndpoint endpoint, String anchorLegUuid, String conferenceName) {
        requireCallId(anchorLegUuid);
        requireConferenceName(conferenceName);
        sendCommand(endpoint, "api uuid_transfer " + anchorLegUuid + " -both 'conference:"
            + conferenceName + "@default' inline");
        log.info("FreeSWITCH 双人通话升级会议命令已提交，anchorLegUuid={}，conferenceName={}",
            anchorLegUuid, conferenceName);
    }

    @Override
    public void joinCallToConference(EslEndpoint endpoint, String callId, String conferenceName) {
        requireCallId(callId);
        requireConferenceName(conferenceName);
        sendCommand(endpoint, "api uuid_transfer " + callId + " 'conference:"
            + conferenceName + "@default' inline");
        log.info("FreeSWITCH 单电话腿加入会议命令已提交，callId={}，conferenceName={}", callId, conferenceName);
    }

    @Override
    public void originateConferenceParticipant(EslEndpoint endpoint, String businessCallId, String participantLegUuid,
                                               String conferenceName, String extension, String callerIdNumber) {
        requireDialValue(businessCallId);
        requireCallId(participantLegUuid);
        requireConferenceName(conferenceName);
        requireDialValue(extension);
        requireDialValue(callerIdNumber);
        requireDialValue(endpoint.sipDomain());
        String variables = "{origination_uuid=" + participantLegUuid
            + ",callnexus_business_call_id=" + businessCallId
            + ",callnexus_direction=INTERNAL"
            + ",callnexus_call_purpose=CALL_CONFERENCE_MEMBER"
            + ",callnexus_conference_name=" + conferenceName
            + ",callnexus_original_caller=" + callerIdNumber
            + ",callnexus_original_called=" + extension
            + ",origination_caller_id_number=" + callerIdNumber
            + ",origination_caller_id_name=多方通话"
            + ",hangup_after_bridge=true}";
        String command = "bgapi originate " + variables + userDialString(extension, endpoint.sipDomain())
            + " &conference(" + conferenceName + "@default)";
        sendCommand(endpoint, command);
        log.info("FreeSWITCH 多方通话邀请已提交，businessCallId={}，participantLegUuid={}，extension={}，conferenceName={}",
            businessCallId, participantLegUuid, extension, conferenceName);
    }

    @Override
    public String conferenceMemberList(EslEndpoint endpoint, String conferenceName) {
        requireConferenceName(conferenceName);
        return executeApiCommandForResult(endpoint, "api conference " + conferenceName + " json_list");
    }

    @Override
    public void muteConferenceMember(EslEndpoint endpoint, String conferenceName,
                                     String conferenceMemberId, boolean muted) {
        requireConferenceName(conferenceName);
        requireConferenceMemberId(conferenceMemberId);
        sendCommand(endpoint, "api conference " + conferenceName + " "
            + (muted ? "mute " : "unmute ") + conferenceMemberId);
        log.info("FreeSWITCH 会议成员静音状态已提交，conferenceName={}，memberId={}，muted={}",
            conferenceName, conferenceMemberId, muted);
    }

    @Override
    public void removeConferenceMember(EslEndpoint endpoint, String conferenceName, String conferenceMemberId) {
        requireConferenceName(conferenceName);
        requireConferenceMemberId(conferenceMemberId);
        sendCommand(endpoint, "api conference " + conferenceName + " kick " + conferenceMemberId);
        log.info("FreeSWITCH 会议成员移除命令已提交，conferenceName={}，memberId={}",
            conferenceName, conferenceMemberId);
    }

    @Override
    public void terminateConference(EslEndpoint endpoint, String conferenceName) {
        requireConferenceName(conferenceName);
        sendCommand(endpoint, "api conference " + conferenceName + " hup all");
        log.info("FreeSWITCH 调度会议全员挂断命令已提交，conferenceName={}", conferenceName);
    }

    @Override
    public void originateDispatchPlayback(EslEndpoint endpoint, String businessCallId, String targetLegUuid,
                                          String extension, String callerIdNumber, String mediaPath,
                                          Long dispatchTaskId, Long dispatchTargetId) {
        requireDialValue(businessCallId);
        requireCallId(targetLegUuid);
        requireDialValue(extension);
        requireDialValue(callerIdNumber);
        requireBroadcastMediaPath(mediaPath);
        if (dispatchTaskId == null || dispatchTargetId == null) {
            throw new ServiceException("调度广播任务和目标ID不能为空");
        }
        String variables = "{origination_uuid=" + targetLegUuid
            + ",callnexus_business_call_id=" + businessCallId
            + ",callnexus_direction=INTERNAL"
            + ",callnexus_call_purpose=DISPATCH_BROADCAST_TARGET"
            + ",callnexus_dispatch_task_id=" + dispatchTaskId
            + ",callnexus_dispatch_target_id=" + dispatchTargetId
            + ",callnexus_original_caller=" + callerIdNumber
            + ",callnexus_original_called=" + extension
            + ",origination_caller_id_number=" + callerIdNumber
            + ",origination_caller_id_name=调度广播"
            + ",hangup_after_bridge=true}";
        String command = "bgapi originate " + variables + userDialString(extension, endpoint.sipDomain())
            + " &playback(" + mediaPath + ")";
        sendCommand(endpoint, command);
        log.info("FreeSWITCH 调度广播目标命令已提交，businessCallId={}，taskId={}，targetId={}，targetLegUuid={}，extension={}，mediaPath={}",
            businessCallId, dispatchTaskId, dispatchTargetId, targetLegUuid, extension, mediaPath);
    }

    void executeApiCommand(EslEndpoint endpoint, String command) {
        requireSuccess(executeCommand(endpoint, command), "FREESWITCH_ESL_COMMAND_FAILED", command);
    }

    String executeApiCommandForResult(EslEndpoint endpoint, String command) {
        EslFrame response = executeCommand(endpoint, command);
        String result = response.body();
        if (result == null || result.isBlank()) {
            result = response.header("Reply-Text");
        }
        return result == null ? "" : result.trim();
    }

    void executeApiCommandIgnoringApplicationError(EslEndpoint endpoint, String command) {
        EslFrame response = executeCommand(endpoint, command);
        log.debug("FreeSWITCH ESL 幂等命令已执行，忽略应用层返回，command={}，response={}", command, response.body());
    }

    public void sendRawCommand(EslEndpoint endpoint, String command) {
        if (command == null || command.isBlank()) {
            throw new ServiceException("FreeSWITCH ESL 命令不能为空");
        }
        sendCommand(endpoint, command);
    }

    /**
     * 精准清理 Sofia profile 中指定 SIP 身份的入站注册。
     */
    public void flushInboundRegistration(EslEndpoint endpoint, String identity, String domain) {
        requireDialValue(identity);
        requireDialValue(domain);
        executeApiCommandIgnoringApplicationError(endpoint,
            "api sofia profile internal flush_inbound_reg " + identity + "@" + domain);
    }

    private void sendCommand(EslEndpoint endpoint, String command) {
        requireSuccess(executeCommand(endpoint, command), "FREESWITCH_ESL_COMMAND_FAILED", command);
    }

    private String intercomAutoAnswerVariables(String purpose) {
        if (!"DISPATCH_INTERCOM_TARGET".equals(purpose)) {
            return "";
        }
        return ",sip_auto_answer=true"
            + ",sip_h_Alert-Info=intercom"
            + ",sip_h_Call-Info=<sip:intercom>;answer-after=0";
    }

    EslFrame executeCommand(EslEndpoint endpoint, String command) {
        requireEndpoint(endpoint);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            try (BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
                EslFrame greeting = readFrame(input);
                if (!"auth/request".equalsIgnoreCase(greeting.header("Content-Type"))) {
                    handleUnexpectedGreeting(endpoint, greeting);
                }
                write(output, "auth " + endpoint.password());
                requireSuccess(readFrame(input), "FREESWITCH_ESL_AUTH_FAILED", "auth ******");
                write(output, command);
                return readFrame(input);
            }
        } catch (IOException exception) {
            log.error("FreeSWITCH ESL command failed, host={}, port={}", endpoint.host(), endpoint.port(), exception);
            throw new ServiceException("连接 FreeSWITCH ESL 失败");
        }
    }

    private void requireCallId(String callId) {
        if (callId == null || !callId.matches("^[0-9a-fA-F-]{36}$")) {
            throw new ServiceException("通话 ID 不合法");
        }
    }

    private void requireConferenceName(String conferenceName) {
        if (conferenceName == null || !conferenceName.matches("^[A-Za-z0-9_-]{1,128}$")) {
            throw new ServiceException("会议房间名称不合法");
        }
    }

    private void requireConferenceMemberId(String conferenceMemberId) {
        if (conferenceMemberId == null || !conferenceMemberId.matches("^\\d{1,12}$")) {
            throw new ServiceException("会议成员 ID 不合法");
        }
    }

    private void requireEndpoint(EslEndpoint endpoint) {
        if (endpoint.host() == null || endpoint.host().isBlank() || endpoint.port() <= 0 || endpoint.port() > 65535
            || endpoint.password() == null || endpoint.password().isBlank()) {
            throw new ServiceException("FreeSWITCH 节点 ESL 未配置");
        }
        if (endpoint.password().contains("\r") || endpoint.password().contains("\n")) {
            throw new ServiceException("FreeSWITCH ESL 密码错误");
        }
    }

    private void requireBroadcastMediaPath(String mediaPath) {
        if (mediaPath == null || mediaPath.isBlank() || mediaPath.contains(" ") || mediaPath.contains(";")
            || mediaPath.contains("&") || mediaPath.contains("|") || mediaPath.contains("\r") || mediaPath.contains("\n")) {
            throw new ServiceException("FREESWITCH_BROADCAST_MEDIA_PATH_INVALID");
        }
    }

    private String requireBroadcastLeg(String leg) {
        if (leg == null || leg.isBlank()) {
            return "aleg";
        }
        String safeLeg = leg.toLowerCase();
        if (!"aleg".equals(safeLeg) && !"bleg".equals(safeLeg) && !"both".equals(safeLeg)) {
            throw new ServiceException("FREESWITCH_BROADCAST_LEG_INVALID");
        }
        return safeLeg;
    }

    private void handleUnexpectedGreeting(EslEndpoint endpoint, EslFrame greeting) {
        String contentType = greeting.header("Content-Type");
        log.warn("Unexpected FreeSWITCH ESL greeting, host={}, port={}, contentType={}, headers={}, body={}",
            endpoint.host(), endpoint.port(), contentType, greeting.headers(), greeting.body());
        if ("text/disconnect-notice".equalsIgnoreCase(contentType) || "text/rude-rejection".equalsIgnoreCase(contentType)) {
            throw new ServiceException("FreeSWITCH ESL 鉴权被拒绝");
        }
        throw new ServiceException("未收到 FreeSWITCH ESL 鉴权请求");
    }

    private void requireDialValue(String value) {
        if (value == null || !value.matches("^[A-Za-z0-9._*#+-]{1,128}$")) {
            throw new ServiceException("拨号参数不合法");
        }
    }

    private void requireSafeSpeechValue(String value, String field) {
        if (value == null || value.isBlank() || value.contains("\r") || value.contains("\n")
            || value.contains(";") || value.contains("&") || value.contains("|")) {
            throw new ServiceException("FreeSWITCH speech parameter invalid: " + field);
        }
    }

    private void requireDtmfDigits(String digits) {
        if (digits == null || !digits.matches("^[0-9A-Da-d*#]{1,32}$")) {
            throw new ServiceException("DTMF 按键不合法");
        }
    }

    private String userDialString(String extension, String domain) {
        return "user/" + extension + "@" + domain;
    }

    private String internalEndpointDialString(String extension, String domain) {
        return "[absolute_codec_string=" + INTERNAL_ENDPOINT_CODEC + "]" + userDialString(extension, domain);
    }

    private String destinationDialString(EslEndpoint endpoint, String destination, String domain, OutboundRoute outboundRoute,
                                         String businessCallId, String originalCaller) {
        if (outboundRoute == null || !outboundRoute.isExternal()) {
            return "[absolute_codec_string=" + INTERNAL_ENDPOINT_CODEC
                + ",callnexus_business_call_id=" + businessCallId
                + ",callnexus_direction=INTERNAL"
                + ",callnexus_original_caller=" + originalCaller
                + ",callnexus_original_called=" + destination + "]"
                + userDialString(destination, domain);
        }
        requireDialValue(outboundRoute.getGatewayCode());
        requireDialValue(outboundRoute.getCallerIdNumber());
        String callerIdNumber = outboundRoute.getCallerIdNumber();
        String originateVariables = "{ignore_early_media=true"
            + ",originate_timeout=" + OUTBOUND_RING_TIMEOUT_SECONDS
            + ",origination_caller_id_number=" + callerIdNumber
            + ",origination_caller_id_name=" + callerIdNumber
            + ",effective_caller_id_number=" + callerIdNumber
            + ",effective_caller_id_name=" + callerIdNumber
            + ",absolute_codec_string=" + OUTBOUND_GATEWAY_CODEC
            + ",codec_string=" + OUTBOUND_GATEWAY_CODEC + "}";
        if (OutboundGatewayDialString.DEVICE_REGISTER.equals(outboundRoute.getGatewayAccessMode())) {
            String identity = outboundRoute.getRegisteredIdentity();
            String sipProfile = outboundRoute.getGatewaySipProfile();
            String sipDomain = outboundRoute.getSipDomain();
            requireDialValue(identity);
            requireDialValue(sipProfile);
            requireDialValue(sipDomain);
            String contact = executeApiCommandForResult(endpoint,
                "api eval ${sofia_contact(" + sipProfile + "/" + identity + "@" + sipDomain + ")}");
            String deviceDialString = OutboundGatewayDialString.buildFromRegisteredContact(contact, destination);
            log.info("设备注册线路实时路由已解析，gatewayCode={}，registeredIdentity={}，destination={}，contact={}，dialString={}",
                outboundRoute.getGatewayCode(), identity, destination, contact, deviceDialString);
            return originateVariables + deviceDialString;
        }
        return originateVariables + OutboundGatewayDialString.build(outboundRoute.getGatewayAccessMode(),
            outboundRoute.getGatewayCode(), outboundRoute.getRegisteredIdentity(), outboundRoute.getGatewaySipProfile(),
            outboundRoute.getSipDomain(), destination);
    }

    private String optionalVariable(String name, Long value) {
        return value == null ? "" : "," + name + "=" + value;
    }

    private String businessCallId(String fallbackCallId, CallOriginateContext context) {
        if (context != null && context.businessCallId() != null && !context.businessCallId().isBlank()) {
            requireDialValue(context.businessCallId());
            return context.businessCallId();
        }
        return fallbackCallId;
    }

    private void write(BufferedOutputStream output, String command) throws IOException {
        output.write((command + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private void requireSuccess(EslFrame frame, String errorCode, String command) {
        String response = frame.header("Reply-Text");
        if (response == null || response.isBlank()) {
            response = frame.body();
        }
        if (!isSuccessResponse(response)) {
            log.warn("FreeSWITCH ESL 命令执行失败，command={}，response={}", command, response);
            throw new ServiceException("FreeSWITCH ESL 命令执行失败，命令=" + safeCommandSummary(command)
                + "，响应=" + errorSummary(response, errorCode));
        }
        log.info("FreeSWITCH ESL 命令执行成功，command={}，response={}", command, response);
    }

    private String safeCommandSummary(String command) {
        if (command == null || command.isBlank()) {
            return "未知命令";
        }
        String summary = command.replace('\r', ' ').replace('\n', ' ').trim();
        return summary.length() <= 300 ? summary : summary.substring(0, 300);
    }

    private String errorSummary(String response, String fallback) {
        if (response == null || response.isBlank()) {
            return fallback;
        }
        String summary = response.replace('\r', ' ').replace('\n', ' ').trim();
        return summary.length() <= 500 ? summary : summary.substring(0, 500);
    }

    private boolean isSuccessResponse(String response) {
        if (response == null) {
            return false;
        }
        String trimmed = response.trim();
        return trimmed.startsWith("+OK") || trimmed.contains("\n+OK") || trimmed.contains("[Success]");
    }

    private EslFrame readFrame(BufferedInputStream input) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while ((line = readLine(input)) != null && !line.isEmpty()) {
            int separator = line.indexOf(':');
            if (separator > 0) {
                headers.put(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
            }
        }
        int contentLength = Integer.parseInt(headers.getOrDefault("Content-Length", "0"));
        byte[] body = input.readNBytes(contentLength);
        return new EslFrame(headers, new String(body, StandardCharsets.UTF_8));
    }

    private String readLine(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int current;
        while ((current = input.read()) != -1) {
            if (current == '\n') {
                break;
            }
            if (current != '\r') {
                line.write(current);
            }
        }
        if (current == -1 && line.size() == 0) {
            return null;
        }
        return line.toString(StandardCharsets.UTF_8);
    }

    private record EslFrame(Map<String, String> headers, String body) {
        private String header(String name) {
            return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        }
    }
}
