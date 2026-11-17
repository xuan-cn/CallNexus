package org.dromara.esl.infrastructure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.call.constant.EslEventNames;
import org.dromara.call.constant.EslHeaders;
import org.dromara.esl.dispatcher.EslEventDispatcher;
import org.dromara.esl.domain.FreeSwitchEslEvent;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Component
@RequiredArgsConstructor
public class FreeSwitchEslEventListenerManager implements SmartLifecycle {
    private static final int CONNECT_TIMEOUT_MILLIS = 5000;
    private static final long RECONNECT_DELAY_MILLIS = 5000;
    private static final String CHANNEL_EVENT_COMMAND =
        "event plain " + String.join(" ", EslEventNames.subscribedChannelEvents());
    private static final String CUSTOM_EVENT_COMMAND =
        "event plain CUSTOM " + String.join(" ", EslEventNames.subscribedCustomEventSubclasses());
    private static final String EVENT_BODY_HEADER = "CallNexus-Event-Body";

    private final FreeSwitchNodeQueryService nodeQueryService;
    private final EslEventDispatcher eventDispatcher;
    private final FreeSwitchRuntimeReconciler runtimeReconciler;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<Long, NodeListener> listeners = new LinkedHashMap<>();
    private volatile boolean running;

    @Override
    public synchronized void start() {
        if (running) return;
        running = true;
        executor.submit(this::refreshListeners);
    }

    @Override
    public synchronized void stop() {
        running = false;
        listeners.values().forEach(NodeListener::cancel);
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
                reconcileListeners(nodeQueryService.listEnabledConnections());
            } catch (RejectedExecutionException exception) {
                if (running) {
                    log.error("Failed to submit FreeSWITCH ESL listener task", exception);
                }
                return;
            } catch (Exception exception) {
                log.error("Failed to refresh FreeSWITCH ESL listeners", exception);
            }
            sleep(30000);
        }
    }

    synchronized void reconcileListeners(List<FreeSwitchNodeConnectionResponse> nodes) {
        if (!running || executor.isShutdown()) return;
        Map<Long, FreeSwitchNodeConnectionResponse> desired = new LinkedHashMap<>();
        nodes.forEach(node -> desired.put(node.getNodeId(), node));
        listeners.entrySet().removeIf(entry -> {
            NodeListener listener = entry.getValue();
            if (listener.matches(desired.get(entry.getKey())) && !listener.task.isDone()) return false;
            listener.cancel();
            return true;
        });
        for (FreeSwitchNodeConnectionResponse node : desired.values()) {
            if (listeners.containsKey(node.getNodeId())) continue;
            NodeListener listener = new NodeListener(node);
            listener.task = executor.submit(() -> listenWithReconnect(listener));
            listeners.put(node.getNodeId(), listener);
        }
    }

    private void listenWithReconnect(NodeListener listener) {
        FreeSwitchNodeConnectionResponse node = listener.node;
        while (running && !listener.cancelled && !Thread.currentThread().isInterrupted()) {
            try {
                listen(listener);
            } catch (Exception exception) {
                if (!running || listener.cancelled) return;
                log.error("FreeSWITCH ESL event listener disconnected, nodeId={}, host={}, port={}",
                    node.getNodeId(), node.getEslHost(), node.getEslPort(), exception);
                sleep(RECONNECT_DELAY_MILLIS);
            }
        }
    }

    private void listen(NodeListener listener) throws IOException {
        FreeSwitchNodeConnectionResponse node = listener.node;
        try (Socket socket = new Socket()) {
            if (!listener.attach(socket)) return;
            socket.connect(new InetSocketAddress(node.getEslHost(), node.getEslPort()), CONNECT_TIMEOUT_MILLIS);
            try (BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                 BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
                requireContentType(readFrame(input), "auth/request");
                write(output, "auth " + node.getEslPassword());
                requireAccepted(readFrame(input));
                write(output, CHANNEL_EVENT_COMMAND);
                requireAccepted(readFrame(input));
                write(output, CUSTOM_EVENT_COMMAND);
                requireAccepted(readFrame(input));
                if (listener.cancelled) return;
                log.info("FreeSWITCH ESL event listener connected, nodeId={}, host={}, port={}, customSubclasses={}",
                    node.getNodeId(), node.getEslHost(), node.getEslPort(), EslEventNames.subscribedCustomEventSubclasses());
                executor.submit(() -> {
                    if (!listener.cancelled) runtimeReconciler.reconcileWithRetry(node.getNodeId());
                });
                while (running && !listener.cancelled && !socket.isClosed()) {
                    EslFrame frame = readFrame(input);
                    if (frame.headers().isEmpty() && frame.body().isEmpty()) {
                        throw new IOException("FreeSWITCH ESL connection closed");
                    }
                    if (!listener.cancelled && "text/event-plain".equalsIgnoreCase(frame.header("Content-Type"))) {
                        handleEvent(node.getNodeId(), frame.body());
                    }
                }
            }
        }
    }

    private void handleEvent(Long nodeId, String body) {
        Map<String, String> headers = parseHeaders(body);
        String eventName = headers.get(EslHeaders.EVENT_NAME);
        if (eventName == null) return;
        String eventBody = extractEventBody(body, headers);
        if (eventBody != null) {
            headers.put(EVENT_BODY_HEADER, eventBody);
        }
        eventDispatcher.dispatch(new FreeSwitchEslEvent(nodeId, eventName, headers));
    }

    private String extractEventBody(String body, Map<String, String> headers) {
        String contentLength = headers.get("Content-Length");
        if (contentLength == null || contentLength.isBlank()) {
            return null;
        }
        int separator = body.indexOf("\n\n");
        if (separator < 0) {
            return null;
        }
        int start = separator + 2;
        return start < body.length() ? body.substring(start) : null;
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

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Socket is already closed.
        }
    }

    private static final class NodeListener {
        private final FreeSwitchNodeConnectionResponse node;
        private volatile boolean cancelled;
        private Socket socket;
        private Future<?> task;

        private NodeListener(FreeSwitchNodeConnectionResponse node) {
            this.node = node;
        }

        private boolean matches(FreeSwitchNodeConnectionResponse desired) {
            return desired != null && Objects.equals(node.getEslHost(), desired.getEslHost())
                && Objects.equals(node.getEslPort(), desired.getEslPort())
                && Objects.equals(node.getEslPassword(), desired.getEslPassword());
        }

        private synchronized boolean attach(Socket socket) {
            if (cancelled) return false;
            this.socket = socket;
            return true;
        }

        private synchronized void cancel() {
            cancelled = true;
            if (socket != null) closeQuietly(socket);
            task.cancel(true);
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
