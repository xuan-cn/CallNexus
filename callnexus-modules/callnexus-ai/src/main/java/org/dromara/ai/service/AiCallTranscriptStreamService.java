package org.dromara.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiCallTranscriptSegment;
import org.dromara.ai.domain.response.AiCallTranscriptSegmentResponse;
import org.dromara.ai.domain.response.AiCallTranscriptStreamEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class AiCallTranscriptStreamService {

    private static final long STREAM_TIMEOUT_MILLIS = 30 * 60 * 1000L;

    private final ConcurrentHashMap<String, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String tenantId, Long callSessionId) {
        String key = key(tenantId, callSessionId);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        subscribers.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).add(emitter);
        emitter.onCompletion(() -> remove(key, emitter));
        emitter.onTimeout(() -> remove(key, emitter));
        emitter.onError(error -> remove(key, emitter));
        try {
            emitter.send(SseEmitter.event()
                .name("connected")
                .data(new AiCallTranscriptStreamEvent(callSessionId, null, null)));
        } catch (IOException | IllegalStateException exception) {
            remove(key, emitter);
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    public void publishSegment(String tenantId, Long callSessionId, Long transcriptId,
                               AiCallTranscriptSegment segment) {
        String key = key(tenantId, callSessionId);
        Set<SseEmitter> emitters = subscribers.get(key);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        AiCallTranscriptStreamEvent event = new AiCallTranscriptStreamEvent(
            callSessionId, transcriptId, segmentResponse(segment));
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("segment").data(event));
            } catch (IOException | IllegalStateException exception) {
                remove(key, emitter);
                log.debug("Remove closed transcript stream, callSessionId={}, error={}",
                    callSessionId, exception.getMessage());
            }
        }
    }

    private AiCallTranscriptSegmentResponse segmentResponse(AiCallTranscriptSegment segment) {
        AiCallTranscriptSegmentResponse response = new AiCallTranscriptSegmentResponse();
        response.setId(segment.getId());
        response.setSpeaker(segment.getSpeaker());
        response.setSourceType(segment.getSourceType());
        response.setLegUuid(segment.getLegUuid());
        response.setAgentId(segment.getAgentId());
        response.setSentenceIndex(segment.getSentenceIndex());
        response.setStartMs(segment.getStartMs());
        response.setEndMs(segment.getEndMs());
        response.setMessageTime(segment.getMessageTime());
        response.setTextContent(segment.getTextContent());
        response.setFinalResult(segment.getFinalResult());
        response.setConfidence(segment.getConfidence());
        return response;
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

    private String key(String tenantId, Long callSessionId) {
        return tenantId + ':' + callSessionId;
    }
}
