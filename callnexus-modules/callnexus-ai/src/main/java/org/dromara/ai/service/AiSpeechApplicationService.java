package org.dromara.ai.service;

import org.dromara.ai.domain.request.*;
import org.dromara.ai.domain.response.*;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface AiSpeechApplicationService {
    List<AiSpeechProviderResponse> providers();
    Long createProvider(AiSpeechProviderRequest request);
    void updateProvider(Long id, AiSpeechProviderRequest request);
    void deleteProvider(Long id);
    TtsTestResponse testProvider(Long id, TtsTestRequest request);
    List<String> providerVoices(Long id);
    AsrTestResponse testAsrProvider(Long id, MultipartFile file, String format, Integer sampleRate);

    List<AiSpeechTemplateResponse> templates();
    Long createTemplate(AiSpeechTemplateRequest request);
    void updateTemplate(Long id, AiSpeechTemplateRequest request);
    void deleteTemplate(Long id);

    TableDataInfo<AiSpeechTaskResponse> tasks(AiSpeechTaskPageQuery query, PageQuery pageQuery);
    AiGeneratedMediaResponse generateAgentNumberPrompt(Long agentId, String extension, List<Long> nodeGroupIds, Long templateId);
    AiGeneratedMediaResponse agentNumberPrompt(Long agentId, Long nodeId);
    AiCallTranscriptResponse callTranscript(Long callSessionId);
    AiCallTranscriptResponse callTranscriptByBusinessCallId(String businessCallId);
    AiCallTranscriptResponse transcribeCallRecording(Long callSessionId);
}
