package org.dromara.esl.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.dromara.call.domain.EslEndpoint;
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
    public void originate(EslEndpoint endpoint, String callId, String agentExtension, String destination) {
        requireDialValue(agentExtension);
        requireDialValue(destination);
        requireDialValue(endpoint.sipDomain());
        String variables = "{origination_uuid=" + callId
            + ",origination_caller_id_number=" + agentExtension
            + ",hangup_after_bridge=true}";
        String command = "bgapi originate " + variables + userDialString(agentExtension, endpoint.sipDomain())
            + " &bridge(" + userDialString(destination, endpoint.sipDomain()) + ")";
        sendCommand(endpoint, command);
        log.info("FreeSWITCH originate command accepted, callId={}, agentExtension={}, destination={}",
            callId, agentExtension, destination);
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

    private void sendCommand(EslEndpoint endpoint, String command) {
        requireSuccess(executeCommand(endpoint, command), "FREESWITCH_ESL_COMMAND_FAILED");
    }

    private EslFrame executeCommand(EslEndpoint endpoint, String command) {
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
                requireSuccess(readFrame(input), "FREESWITCH_ESL_AUTH_FAILED");
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

    private void write(BufferedOutputStream output, String command) throws IOException {
        output.write((command + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private void requireSuccess(EslFrame frame, String errorCode) {
        String response = frame.header("Reply-Text");
        if (response == null || response.isBlank()) {
            response = frame.body();
        }
        if (response == null || !response.trim().startsWith("+OK")) {
            log.warn("FreeSWITCH ESL rejected command: {}", response);
            throw new ServiceException(errorCode);
        }
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
