package org.dromara.call.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.dromara.ai.domain.AiCallRecordingSource;
import org.dromara.ai.domain.AiCallTranscript;
import org.dromara.ai.domain.AiCallTranscriptSegment;
import org.dromara.ai.domain.AiRealtimeCallSession;
import org.dromara.ai.domain.event.StreamingAsrTranscriptEvent;
import org.dromara.ai.mapper.AiCallRecordingSourceMapper;
import org.dromara.ai.mapper.AiCallTranscriptMapper;
import org.dromara.ai.mapper.AiCallTranscriptSegmentMapper;
import org.dromara.ai.mapper.AiRealtimeCallSessionMapper;
import org.dromara.ai.domain.request.AiAgentAssistSegmentRequest;
import org.dromara.ai.domain.response.AiCallTranscriptSegmentResponse;
import org.dromara.ai.service.AiAgentAssistService;
import org.dromara.ai.service.AiAgentAssistStreamService;
import org.dromara.ai.service.AiCallTranscriptStreamService;
import org.dromara.ai.provider.AsrSegment;
import org.dromara.agent.domain.CallQueue;
import org.dromara.agent.domain.SkillGroup;
import org.dromara.agent.domain.SkillGroupMember;
import org.dromara.agent.mapper.CallQueueMapper;
import org.dromara.agent.mapper.SkillGroupMapper;
import org.dromara.agent.mapper.SkillGroupMemberMapper;
import org.dromara.call.constant.EslEventNames;
import org.dromara.call.domain.CallLeg;
import org.dromara.call.domain.CallSession;
import org.dromara.call.domain.TelephonyEvent;
import org.dromara.call.mapper.CallLegMapper;
import org.dromara.call.mapper.CallSessionMapper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.StringReader;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class CallRealtimeTranscriptEventService {

    private static final String EVENT_BODY_HEADER = "CallNexus-Event-Body";
    private static final String SPEECH_TYPE_HEADER = "Speech-Type";
    private static final String SPEECH_TYPE_DETECTED = "detected-speech";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String PROVIDER_UNIMRCP = "UNIMRCP";
    private static final String SOURCE_REALTIME_ASR = "REALTIME_ASR";
    private static final String SPEAKER_CUSTOMER = "CUSTOMER";
    private static final String SPEAKER_AGENT = "AGENT";
    private static final String SPEAKER_UNKNOWN = "UNKNOWN";

    private final FreeSwitchNodeQueryService nodeQueryService;
    private final CallLegMapper callLegMapper;
    private final AiRealtimeCallSessionMapper realtimeCallSessionMapper;
    private final AiCallRecordingSourceMapper recordingSourceMapper;
    private final AiCallTranscriptMapper transcriptMapper;
    private final AiCallTranscriptSegmentMapper transcriptSegmentMapper;
    private final AiCallTranscriptStreamService transcriptStreamService;
    private final AiAgentAssistStreamService agentAssistStreamService;
    private final CallSessionMapper callSessionMapper;
    private final CallQueueMapper callQueueMapper;
    private final SkillGroupMapper skillGroupMapper;
    private final SkillGroupMemberMapper skillGroupMemberMapper;
    private final AiAgentAssistService agentAssistService;

    public void handle(TelephonyEvent event) {
        if (!EslEventNames.DETECTED_SPEECH.equals(event.eventName())) {
            return;
        }
        String tenantId = nodeQueryService.findTenantId(event.nodeId());
        if (StringUtils.isBlank(tenantId)) {
            log.debug("Skip realtime transcript event without tenant, nodeId={}, uuid={}", event.nodeId(), event.uuid());
            return;
        }
        TenantHelper.dynamic(tenantId, () -> handleInTenant(tenantId, event));
    }

    @EventListener
    public void handleStreamingAsr(StreamingAsrTranscriptEvent event) {
        if (event == null || StringUtils.isBlank(event.tenantId()) || event.segment() == null
            || !event.segment().finalResult() || StringUtils.isBlank(event.segment().text())) {
            return;
        }
        TenantHelper.dynamic(event.tenantId(), () -> handleStreamingAsrInTenant(event));
    }

    private void handleInTenant(String tenantId, TelephonyEvent event) {
        String speechType = header(event.headers(), SPEECH_TYPE_HEADER);
        if (StringUtils.isNotBlank(speechType) && !SPEECH_TYPE_DETECTED.equalsIgnoreCase(speechType)) {
            return;
        }
        String text = parseRecognizedText(event.headers());
        if (StringUtils.isBlank(text)) {
            return;
        }

        CallLeg leg = callLegMapper.selectOne(new LambdaQueryWrapper<CallLeg>()
            .eq(CallLeg::getNodeId, event.nodeId())
            .eq(CallLeg::getLegUuid, event.uuid())
            .last("limit 1"));
        if (leg == null || leg.getSessionId() == null || StringUtils.isBlank(leg.getBusinessCallId())) {
            log.debug("Skip realtime transcript event without call leg, nodeId={}, uuid={}", event.nodeId(), event.uuid());
            return;
        }
        if (isAiRealtimeLeg(leg)) {
            return;
        }

        saveSegment(tenantId, leg, PROVIDER_UNIMRCP,
            new AsrSegment(null, null, null, text.trim(), null, true));
    }

    private void handleStreamingAsrInTenant(StreamingAsrTranscriptEvent event) {
        CallLeg leg = callLegMapper.selectOne(new LambdaQueryWrapper<CallLeg>()
            .eq(CallLeg::getNodeId, event.nodeId())
            .eq(CallLeg::getLegUuid, event.legUuid())
            .last("limit 1"));
        if (leg == null || leg.getSessionId() == null || StringUtils.isBlank(leg.getBusinessCallId())) {
            log.debug("Skip streaming ASR transcript without call leg, nodeId={}, legUuid={}",
                event.nodeId(), event.legUuid());
            return;
        }
        if (StringUtils.isNotBlank(event.businessCallId())
            && !StringUtils.equals(event.businessCallId(), leg.getBusinessCallId())) {
            log.warn("Skip streaming ASR transcript with mismatched call, tokenBusinessCallId={}, actualBusinessCallId={}, legUuid={}",
                event.businessCallId(), leg.getBusinessCallId(), event.legUuid());
            return;
        }
        String actualSpeaker = speaker(leg);
        if (StringUtils.isNotBlank(event.speaker()) && !StringUtils.equals(event.speaker(), actualSpeaker)) {
            log.warn("Streaming ASR speaker corrected from call leg, tokenSpeaker={}, actualSpeaker={}, legUuid={}",
                event.speaker(), actualSpeaker, event.legUuid());
        }
        if (isAiRealtimeLeg(leg)) {
            return;
        }
        saveSegment(event.tenantId(), leg,
            StringUtils.defaultIfBlank(event.providerType(), "STREAMING_ASR"), event.segment());
    }

    private void saveSegment(String tenantId, CallLeg leg, String providerType, AsrSegment recognized) {
        AiCallRecordingSource source = recordingSourceMapper.selectOne(new LambdaQueryWrapper<AiCallRecordingSource>()
            .eq(AiCallRecordingSource::getId, leg.getSessionId())
            .last("limit 1"));
        if (source == null) {
            log.debug("Skip realtime transcript event without call session, sessionId={}, businessCallId={}",
                leg.getSessionId(), leg.getBusinessCallId());
            return;
        }

        AiCallTranscript transcript = ensureTranscript(source, providerType);
        AiCallTranscriptSegment segment = new AiCallTranscriptSegment();
        segment.setTranscriptId(transcript.getId());
        segment.setCallSessionId(source.getId());
        segment.setBusinessCallId(source.getBusinessCallId());
        segment.setSpeaker(speaker(leg));
        segment.setSourceType(SOURCE_REALTIME_ASR);
        segment.setLegUuid(leg.getLegUuid());
        segment.setAgentId(leg.getAgentId());
        segment.setSentenceIndex(nextSentenceIndex(transcript.getId()));
        segment.setStartMs(recognized.startMs());
        segment.setEndMs(recognized.endMs());
        segment.setMessageTime(LocalDateTime.now());
        segment.setTextContent(recognized.text().trim());
        segment.setFinalResult(recognized.finalResult());
        segment.setConfidence(recognized.confidence());
        transcriptSegmentMapper.insert(segment);

        transcript.setFullText(appendTranscriptLine(transcript.getFullText(), segment.getSpeaker(), segment.getTextContent()));
        transcript.setStatus(STATUS_SUCCESS);
        transcript.setFinishedAt(LocalDateTime.now());
        transcriptMapper.updateById(transcript);

        transcriptStreamService.publishSegment(tenantId, source.getId(), transcript.getId(), segment);
        agentAssistStreamService.publishSegment(tenantId, source.getBusinessCallId(), transcriptResponse(segment));
        triggerAgentAssist(tenantId, source, segment);
        log.info("Realtime call transcript segment saved, sessionId={}, businessCallId={}, legUuid={}, speaker={}, sentenceIndex={}",
            source.getId(), source.getBusinessCallId(), leg.getLegUuid(), segment.getSpeaker(), segment.getSentenceIndex());
    }

    private void triggerAgentAssist(String tenantId, AiCallRecordingSource source, AiCallTranscriptSegment segment) {
        if (!SPEAKER_CUSTOMER.equals(segment.getSpeaker()) || !Boolean.TRUE.equals(segment.getFinalResult())) {
            return;
        }
        CallSession callSession = callSessionMapper.selectById(source.getId());
        if (callSession == null) {
            return;
        }
        Long agentId = firstNonNull(callSession.getOwnerAgentId(), callSession.getAgentId());
        SkillGroup group = resolveAssistGroup(callSession.getHandlingQueueId(), agentId);
        if (group == null || !Boolean.TRUE.equals(group.getAssistEnabled()) || group.getAssistAgentId() == null) {
            return;
        }
        agentAssistService.accept(new AiAgentAssistSegmentRequest(
            tenantId,
            source.getId(),
            source.getBusinessCallId(),
            segment.getId(),
            segment.getTextContent(),
            agentId,
            group.getId(),
            group.getAssistAgentId()
        ));
    }

    private SkillGroup resolveAssistGroup(Long queueId, Long agentId) {
        if (queueId != null) {
            CallQueue queue = callQueueMapper.selectById(queueId);
            if (queue != null && queue.getSkillGroupId() != null) {
                SkillGroup group = skillGroupMapper.selectById(queue.getSkillGroupId());
                if (isAssistGroup(group)) {
                    return group;
                }
            }
        }
        if (agentId == null) {
            return null;
        }
        List<SkillGroupMember> memberships = skillGroupMemberMapper.selectList(
            new LambdaQueryWrapper<SkillGroupMember>()
                .eq(SkillGroupMember::getAgentId, agentId)
                .orderByAsc(SkillGroupMember::getPriority, SkillGroupMember::getId));
        for (SkillGroupMember membership : memberships) {
            SkillGroup group = skillGroupMapper.selectById(membership.getSkillGroupId());
            if (isAssistGroup(group)) {
                return group;
            }
        }
        return null;
    }

    private boolean isAssistGroup(SkillGroup group) {
        return group != null && Boolean.TRUE.equals(group.getEnabled())
            && Boolean.TRUE.equals(group.getAssistEnabled()) && group.getAssistAgentId() != null;
    }

    private boolean isAiRealtimeLeg(CallLeg leg) {
        return realtimeCallSessionMapper.exists(new LambdaQueryWrapper<AiRealtimeCallSession>()
            .eq(AiRealtimeCallSession::getBusinessCallId, leg.getBusinessCallId())
            .eq(AiRealtimeCallSession::getCustomerLegUuid, leg.getLegUuid())
            .in(AiRealtimeCallSession::getSessionState,
                "INITIALIZING", "LISTENING", "THINKING", "SPEAKING", "TRANSFERRING", "ENDING"));
    }

    private AiCallTranscript ensureTranscript(AiCallRecordingSource source, String providerType) {
        AiCallTranscript transcript = transcriptMapper.selectOne(new LambdaQueryWrapper<AiCallTranscript>()
            .eq(AiCallTranscript::getCallSessionId, source.getId())
            .last("limit 1"));
        if (transcript != null) {
            transcript.setProviderType(StringUtils.defaultIfBlank(transcript.getProviderType(), providerType));
            transcript.setStatus(STATUS_SUCCESS);
            transcript.setFailureReason(null);
            if (transcript.getStartedAt() == null) {
                transcript.setStartedAt(firstNonNull(source.getAnsweredAt(), source.getStartedAt(), LocalDateTime.now()));
            }
            return transcript;
        }

        transcript = new AiCallTranscript();
        transcript.setCallSessionId(source.getId());
        transcript.setBusinessCallId(source.getBusinessCallId());
        transcript.setProviderType(providerType);
        transcript.setInputMediaId(source.getRecordingMediaId());
        transcript.setRecordingOssId(source.getRecordingOssId());
        transcript.setStatus(STATUS_SUCCESS);
        transcript.setStartedAt(firstNonNull(source.getAnsweredAt(), source.getStartedAt(), LocalDateTime.now()));
        transcript.setFinishedAt(LocalDateTime.now());
        transcriptMapper.insert(transcript);
        return transcript;
    }

    private Integer nextSentenceIndex(Long transcriptId) {
        AiCallTranscriptSegment latest = transcriptSegmentMapper.selectOne(new LambdaQueryWrapper<AiCallTranscriptSegment>()
            .eq(AiCallTranscriptSegment::getTranscriptId, transcriptId)
            .orderByDesc(AiCallTranscriptSegment::getSentenceIndex)
            .last("limit 1"));
        return latest == null || latest.getSentenceIndex() == null ? 1 : latest.getSentenceIndex() + 1;
    }

    private String speaker(CallLeg leg) {
        if (SPEAKER_CUSTOMER.equals(leg.getLegRole())) {
            return SPEAKER_CUSTOMER;
        }
        if (SPEAKER_AGENT.equals(leg.getLegRole()) || leg.getAgentId() != null) {
            return SPEAKER_AGENT;
        }
        return SPEAKER_UNKNOWN;
    }

    private String parseRecognizedText(Map<String, String> headers) {
        String direct = firstNonBlank(
            header(headers, "variable_detect_speech_result"),
            header(headers, "variable_speech_result"),
            header(headers, "Detect-Speech-Result"),
            header(headers, "Speech-Result"),
            header(headers, "detect_speech_result"),
            header(headers, "speech_result")
        );
        if (StringUtils.isNotBlank(direct)) {
            return direct.trim();
        }
        return parseNlsmlText(header(headers, EVENT_BODY_HEADER));
    }

    private String parseNlsmlText(String nlsml) {
        if (StringUtils.isBlank(nlsml)) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new InputSource(new StringReader(nlsml)));
            XPath xpath = XPathFactory.newInstance().newXPath();
            String text = (String) xpath.evaluate("/result/interpretation/input/text()", document, XPathConstants.STRING);
            return StringUtils.isBlank(text) ? null : text.trim();
        } catch (Exception exception) {
            log.warn("Failed to parse DETECTED_SPEECH NLSML, error={}", exception.getMessage());
            return null;
        }
    }

    private String appendTranscriptLine(String current, String speaker, String text) {
        String label = switch (StringUtils.defaultString(speaker)) {
            case SPEAKER_CUSTOMER -> "CUSTOMER";
            case SPEAKER_AGENT -> "AGENT";
            default -> "UNKNOWN";
        };
        String line = label + ": " + text;
        return StringUtils.isBlank(current) ? line : current + "\n" + line;
    }

    private LocalDateTime firstNonNull(LocalDateTime first, LocalDateTime second, LocalDateTime third) {
        if (first != null) {
            return first;
        }
        return second != null ? second : third;
    }

    private AiCallTranscriptSegmentResponse transcriptResponse(AiCallTranscriptSegment segment) {
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

    private Long firstNonNull(Long first, Long second) {
        return first != null ? first : second;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private String header(Map<String, String> headers, String name) {
        if (headers == null || StringUtils.isBlank(name)) {
            return null;
        }
        String direct = headers.get(name);
        if (direct != null) {
            return direct;
        }
        return headers.entrySet().stream()
            .filter(entry -> name.equalsIgnoreCase(entry.getKey()))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);
    }
}
