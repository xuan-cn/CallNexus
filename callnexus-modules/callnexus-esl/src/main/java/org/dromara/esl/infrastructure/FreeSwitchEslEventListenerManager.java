package org.dromara.esl.infrastructure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.call.domain.TelephonyEvent;
import org.dromara.call.service.TelephonyEventHandler;
import org.dromara.resource.node.domain.response.FreeSwitchNodeConnectionResponse;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Slf4j
@Component
@RequiredArgsConstructor
public class FreeSwitchEslEventListenerManager implements SmartLifecycle {
    private static final int CONNECT_TIMEOUT_MILLIS = 5000;
    private static final long RECONNECT_DELAY_MILLIS = 5000;
    private static final String EVENT_COMMAND =
        "event plain CHANNEL_CREATE CHANNEL_PROGRESS CHANNEL_PROGRESS_MEDIA CHANNEL_ANSWER CHANNEL_BRIDGE CHANNEL_HANGUP_COMPLETE";

    private final FreeSwitchNodeQueryService nodeQueryService;
    private final TelephonyEventHandler eventHandler;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<Long, Future<?>> listeners = new ConcurrentHashMap<>();
    private final Map<Long, Socket> sockets = new ConcurrentHashMap<>();
    private volatile boolean running;

    @Override
    public void start() {
        running = true;
        executor.submit(this::refreshListeners);
    }

    @Override
    public void stop() {
        running = false;
        sockets.values().forEach(this::closeQuietly);
        sockets.clear();
        listeners.values().forEach(future -> future.cancel(true));
        listeners.clear();
        executor.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void refreshListeners() {
        while (running) {
            try {
                for (FreeSwitchNodeConnectionResponse node : nodeQueryService.listEnabledConnections()) {
                    listeners.computeIfAbsent(node.getNodeId(), ignored -> executor.submit(() -> listenWithReconnect(node)));
                }
            } catch (Exception exception) {
                log.error("Failed to refresh FreeSWITCH ESL listeners", exception);
            }
            sleep(30000);
        }
    }

    private void listenWithReconnect(FreeSwitchNodeConnectionResponse node) {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                listen(node);
            } catch (Exception exception) {
                log.error("FreeSWITCH ESL event listener disconnected, nodeId={}, host={}, port={}",
                    node.getNodeId(), node.getEslHost(), node.getEslPort(), exception);
                sleep(RECONNECT_DELAY_MILLIS);
            }
        }
    }

    private void listen(FreeSwitchNodeConnectionResponse node) throws IOException {
        try (Socket socket = new Socket()) {
            sockets.put(node.getNodeId(), socket);
            socket.connect(new InetSocketAddress(node.getEslHost(), node.getEslPort()), CONNECT_TIMEOUT_MILLIS);
            try (BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                 BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
                requireContentType(readFrame(input), "auth/request");
                write(output, "auth " + node.getEslPassword());
                requireAccepted(readFrame(input));
                write(output, EVENT_COMMAND);
                requireAccepted(readFrame(input));
                log.info("FreeSWITCH ESL event listener connected, nodeId={}, host={}, port={}",
                    node.getNodeId(), node.getEslHost(), node.getEslPort());
                while (running && !socket.isClosed()) {
                    EslFrame frame = readFrame(input);
                    if (frame.headers().isEmpty() && frame.body().isEmpty()) {
                        throw new IOException("FreeSWITCH ESL connection closed");
                    }
                    if ("text/event-plain".equalsIgnoreCase(frame.header("Content-Type"))) {
                        handleEvent(node.getNodeId(), frame.body());
                    }
                }
            }
        } finally {
            sockets.remove(node.getNodeId());
        }
    }

    private void handleEvent(Long nodeId, String body) {
        Map<String, String> headers = parseHeaders(body);
        String eventName = headers.get("Event-Name");
        if (eventName == null) return;
        try {
            eventHandler.onEvent(new TelephonyEvent(
                nodeId,
                eventName,
                first(headers, "Unique-ID", "Channel-Call-UUID"),
                first(headers, "Caller-Caller-ID-Number", "Caller-Username"),
                first(headers, "Caller-Destination-Number", "variable_sip_to_user"),
                headers.get("Hangup-Cause"),
                headers
            ));
        } catch (Exception exception) {
            log.error("Failed to process FreeSWITCH ESL event, nodeId={}, eventName={}, uuid={}",
                nodeId, eventName, headers.get("Unique-ID"), exception);
        }
    }

    private Map<String, String> parseHeaders(String body) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String line : body.split("\\r?\\n")) {
            int separator = line.indexOf(':');
            if (separator > 0) {
                headers.put(line.substring(0, separator).trim(),
                    URLDecoder.decode(line.substring(separator + 1).trim(), StandardCharsets.UTF_8));
            }
        }
        return headers;
    }

    private String first(Map<String, String> headers, String... names) {
        for (String name : names) {
            String value = headers.get(name);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private void requireContentType(EslFrame frame, String expected) throws IOException {
        if (!expected.equalsIgnoreCase(frame.header("Content-Type"))) {
            throw new IOException("Unexpected ESL content type: " + frame.header("Content-Type"));
        }
    }

    private void requireAccepted(EslFrame frame) throws IOException {
        String reply = frame.header("Reply-Text");
        if (reply == null || !reply.startsWith("+OK")) {
            throw new IOException("FreeSWITCH ESL rejected request: " + reply);
        }
    }

    private void write(BufferedOutputStream output, String command) throws IOException {
        output.write((command + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
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
        return new EslFrame(headers, new String(input.readNBytes(contentLength), StandardCharsets.UTF_8));
    }

    private String readLine(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int current;
        while ((current = input.read()) != -1) {
            if (current == '\n') break;
            if (current != '\r') line.write(current);
        }
        if (current == -1 && line.size() == 0) return null;
        return line.toString(StandardCharsets.UTF_8);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Socket is already closed.
        }
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
