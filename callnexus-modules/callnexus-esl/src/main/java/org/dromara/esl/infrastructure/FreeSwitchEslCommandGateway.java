package org.dromara.esl.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.dromara.call.domain.EslEndpoint;
import org.dromara.call.domain.OutboundRoute;
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

@Slf4j
@Component
public class FreeSwitchEslCommandGateway implements TelephonyCommandGateway {
    private static final int CONNECT_TIMEOUT_MILLIS = 5000;
    private static final int READ_TIMEOUT_MILLIS = 5000;

    @Override
    public void originate(EslEndpoint endpoint, String callId, String agentExtension, String destination, OutboundRoute outboundRoute) {
        requireDialValue(agentExtension);
        requireDialValue(destination);
        requireDialValue(endpoint.sipDomain());
        String callerIdNumber = outboundRoute != null && outboundRoute.isExternal() ? outboundRoute.getCallerIdNumber() : agentExtension;
        requireDialValue(callerIdNumber);
        String variables = "{origination_uuid=" + callId
            + ",callnexus_business_call_id=" + callId
            + ",callnexus_direction=" + (outboundRoute != null && outboundRoute.isExternal() ? "OUTBOUND" : "INTERNAL")
            + ",callnexus_original_caller=" + agentExtension
            + ",callnexus_original_called=" + destination
            + ",origination_caller_id_number=" + callerIdNumber
            + ",origination_caller_id_name=" + callerIdNumber
            + ",execute_on_answer=record_session::/var/lib/freeswitch/recordings/" + callId + ".wav"
            + ",api_hangup_hook=bg_system /opt/callnexus/bin/upload-recording.sh " + callId
            + " /var/lib/freeswitch/recordings/" + callId + ".wav"
            + ",hangup_after_bridge=true}";
        String destinationDialString = destinationDialString(destination, endpoint.sipDomain(), outboundRoute);
        String command = "bgapi originate " + variables + userDialString(agentExtension, endpoint.sipDomain())
            + " &bridge(" + destinationDialString + ")";
        sendCommand(endpoint, command);
        log.info("FreeSWITCH 发起呼叫命令已提交，callId={}，agentExtension={}，destination={}，external={}，gatewayCode={}，callerIdNumber={}",
            callId, agentExtension, destination, outboundRoute != null && outboundRoute.isExternal(),
            outboundRoute == null ? null : outboundRoute.getGatewayCode(), callerIdNumber);
    }

    @Override
    public void hangup(EslEndpoint endpoint, String callId) {
        requireCallId(callId);
        sendCommand(endpoint, "api uuid_kill " + callId + " NORMAL_CLEARING");
        log.info("FreeSWITCH hangup command accepted, callId={}", callId);
    }

    @Override
    public boolean callExists(EslEndpoint endpoint, String callId) {
        requireCallId(callId);
        EslFrame response = executeCommand(endpoint, "api uuid_exists " + callId);
        return "true".equalsIgnoreCase(response.body().trim());
    }

    void executeApiCommand(EslEndpoint endpoint, String command) {
        requireSuccess(executeCommand(endpoint, command), "FREESWITCH_ESL_COMMAND_FAILED", command);
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
            throw new ServiceException("FREESWITCH_ESL_CONNECTION_FAILED");
        }
    }

    private void requireCallId(String callId) {
        if (callId == null || !callId.matches("^[0-9a-fA-F-]{36}$")) {
            throw new ServiceException("INVALID_CALL_ID");
        }
    }

    private void requireEndpoint(EslEndpoint endpoint) {
        if (endpoint.host() == null || endpoint.host().isBlank() || endpoint.port() <= 0 || endpoint.port() > 65535
            || endpoint.password() == null || endpoint.password().isBlank()) {
            throw new ServiceException("FREESWITCH_NODE_ESL_NOT_CONFIGURED");
        }
        if (endpoint.password().contains("\r") || endpoint.password().contains("\n")) {
            throw new ServiceException("FREESWITCH_ESL_PASSWORD_INVALID");
        }
    }

    private void handleUnexpectedGreeting(EslEndpoint endpoint, EslFrame greeting) {
        String contentType = greeting.header("Content-Type");
        log.warn("Unexpected FreeSWITCH ESL greeting, host={}, port={}, contentType={}, headers={}, body={}",
            endpoint.host(), endpoint.port(), contentType, greeting.headers(), greeting.body());
        if ("text/disconnect-notice".equalsIgnoreCase(contentType) || "text/rude-rejection".equalsIgnoreCase(contentType)) {
            throw new ServiceException("FREESWITCH_ESL_ACCESS_DENIED");
        }
        throw new ServiceException("FREESWITCH_ESL_AUTH_REQUEST_NOT_RECEIVED");
    }

    private void requireDialValue(String value) {
        if (value == null || !value.matches("^[A-Za-z0-9._*#+-]{1,128}$")) {
            throw new ServiceException("INVALID_DIAL_VALUE");
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
        return "sofia/gateway/" + outboundRoute.getGatewayCode() + "/" + destination;
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
            throw new ServiceException(errorCode);
        }
        log.info("FreeSWITCH ESL 命令执行成功，command={}，response={}", command, response);
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
