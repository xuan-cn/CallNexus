package org.dromara.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.response.AiAgentAssistStreamEvent;
import org.dromara.ai.domain.response.AiAgentAssistSuggestionResponse;
import org.dromara.ai.domain.response.AiCallTranscriptSegmentResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class AiAgentAssistStreamService {

    private static final long STREAM_TIMEOUT_MILLIS = 30 * 60 * 1000L;
    private final ConcurrentHashMap<String, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String tenantId, String businessCallId) {
        String key = key(tenantId, businessCallId);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        subscribers.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).add(emitter);
        emitter.onCompletion(() -> remove(key, emitter));
        emitter.onTimeout(() -> remove(key, emitter));
        emitter.onError(error -> remove(key, emitter));
        try {
            emitter.send(SseEmitter.event().name("connected")
                .data(new AiAgentAssistStreamEvent(businessCallId, null, null)));
        } catch (IOException | IllegalStateException exception) {
            remove(key, emitter);
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    public void publish(String tenantId, String businessCallId, AiAgentAssistSuggestionResponse suggestion) {
        String key = key(tenantId, businessCallId);
        send(key, businessCallId, "suggestion", new AiAgentAssistStreamEvent(businessCallId, suggestion, null));
    }

    public void publishSegment(String tenantId, String businessCallId, AiCallTranscriptSegmentResponse segment) {
        String key = key(tenantId, businessCallId);
        send(key, businessCallId, "segment", new AiAgentAssistStreamEvent(businessCallId, null, segment));
    }

    private void send(String key, String businessCallId, String eventName, AiAgentAssistStreamEvent event) {
        Set<SseEmitter> emitters = subscribers.get(key);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(event));
            } catch (IOException | IllegalStateException exception) {
                remove(key, emitter);
                log.debug("Remove closed agent assist stream, businessCallId={}, error={}",
                    businessCallId, exception.getMessage());
            }
        }
    }

    private void remove(String key, SseEmitter emitter) {
        Set<SseEmitter> emitters = subscribers.get(key);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            subscribers.remove(key, emitters);
        }
    }

    private String key(String tenantId, String businessCallId) {
        return tenantId + ':' + businessCallId;
    }
}
