package org.dromara.esl.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.dromara.call.domain.EslEndpoint;
import org.dromara.call.domain.OutboundRoute;
import org.dromara.call.domain.CallOriginateContext;
import org.dromara.call.service.TelephonyCommandGateway;
import org.dromara.common.core.exception.ServiceException;
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
    private static final String OUTBOUND_GATEWAY_CODEC = "PCMA";
    private static final Pattern REGISTERED_USER_PATTERN = Pattern.compile("(?m)^User:\\s*([^@\\s]+)@.+$");

    @Override
    public void originate(EslEndpoint endpoint, String callId, String agentExtension, String destination, OutboundRoute outboundRoute,
                          CallOriginateContext context) {
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
        String destinationDialString = destinationDialString(destination, endpoint.sipDomain(), outboundRoute);
        String command = "bgapi originate " + variables + userDialString(agentExtension, endpoint.sipDomain())
            + " &bridge(" + destinationDialString + ")";
        sendCommand(endpoint, command);
        log.info("FreeSWITCH 发起呼叫命令已提交，channelUuid={}，businessCallId={}，agentExtension={}，destination={}，external={}，gatewayCode={}，callerIdNumber={}，customerId={}，outboundTaskId={}，outboundMemberId={}",
            callId, businessCallId, agentExtension, destination, outboundRoute != null && outboundRoute.isExternal(),
            outboundRoute == null ? null : outboundRoute.getGatewayCode(), callerIdNumber,
            safeContext.customerId(), safeContext.outboundTaskId(), safeContext.outboundMemberId());
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
    public void setCallVariable(EslEndpoint endpoint, String callId, String name, String value) {
        requireCallId(callId);
        requireDialValue(name);
        requireDialValue(value);
        sendCommand(endpoint, "api uuid_setvar " + callId + " " + name + " " + value);
        log.info("FreeSWITCH 通话变量已设置，callId={}，name={}，value={}", callId, name, value);
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
    public void originateConsultation(EslEndpoint endpoint, String businessCallId, String consultCallId, String agentExtension, String targetExtension,
                                      String customerLegUuid, String sourceAgentLegUuid) {
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
        String command = "bgapi originate " + variables + userDialString(targetExtension, endpoint.sipDomain())
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

    void executeApiCommand(EslEndpoint endpoint, String command) {
        requireSuccess(executeCommand(endpoint, command), "FREESWITCH_ESL_COMMAND_FAILED", command);
    }

    void executeApiCommandIgnoringApplicationError(EslEndpoint endpoint, String command) {
        EslFrame response = executeCommand(endpoint, command);
        log.debug("FreeSWITCH ESL 幂等命令已执行，忽略应用层返回，command={}，response={}", command, response.body());
    }

    private void sendCommand(EslEndpoint endpoint, String command) {
        requireSuccess(executeCommand(endpoint, command), "FREESWITCH_ESL_COMMAND_FAILED", command);
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

    private void requireDtmfDigits(String digits) {
        if (digits == null || !digits.matches("^[0-9A-Da-d*#]{1,32}$")) {
            throw new ServiceException("DTMF 按键不合法");
        }
    }

    private String userDialString(String extension, String domain) {
        return "user/" + extension + "@" + domain;
    }

    private String destinationDialString(String destination, String domain, OutboundRoute outboundRoute) {
        if (outboundRoute == null || !outboundRoute.isExternal()) {
            return userDialString(destination, domain);
        }
        requireDialValue(outboundRoute.getGatewayCode());
        requireDialValue(outboundRoute.getCallerIdNumber());
        String callerIdNumber = outboundRoute.getCallerIdNumber();
        String legVariables = "[origination_caller_id_number=" + callerIdNumber
            + ",origination_caller_id_name=" + callerIdNumber
            + ",effective_caller_id_number=" + callerIdNumber
            + ",effective_caller_id_name=" + callerIdNumber
            + ",absolute_codec_string=" + OUTBOUND_GATEWAY_CODEC
            + ",codec_string=" + OUTBOUND_GATEWAY_CODEC + "]";
        return legVariables + "sofia/gateway/" + outboundRoute.getGatewayCode() + "/" + destination;
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
