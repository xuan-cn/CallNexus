package org.dromara.openapi.service;

import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.response.AiCallTranscriptResponse;
import org.dromara.ai.service.AiSpeechApplicationService;
import org.dromara.call.domain.response.DispatchCallTopologyResponse;
import org.dromara.call.domain.response.CallRecordingResponse;
import org.dromara.call.service.CallRecordingApplicationService;
import org.dromara.call.service.DispatchCallMonitorService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.openapi.domain.response.OpenApiAgentActiveCallResponse;
import org.dromara.openapi.domain.response.OpenApiCallDetailResponse;
import org.dromara.openapi.domain.response.OpenApiCallResponse;
import org.dromara.openapi.domain.response.OpenApiRecordingResponse;
import org.dromara.openapi.domain.response.OpenApiTranscriptResponse;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OpenApiCallQueryService {
    private final DispatchCallMonitorService monitorService;
    private final CallRecordingApplicationService recordingService;
    private final AiSpeechApplicationService speechService;

    public List<OpenApiCallResponse> listActiveCalls() {
        return monitorService.listActiveCalls().stream().map(OpenApiCallResponse::from).toList();
    }

    public OpenApiCallDetailResponse get(String businessCallId) {
        return OpenApiCallDetailResponse.from(monitorService.getTopology(businessCallId));
    }

    public OpenApiAgentActiveCallResponse getAgentActiveCall(Long agentId) {
        DispatchCallTopologyResponse topology = monitorService.getActiveCallByAgentId(agentId);
        return new OpenApiAgentActiveCallResponse(agentId, topology != null,
            topology == null ? null : OpenApiCallDetailResponse.from(topology));
    }

    public List<OpenApiRecordingResponse> recordings(String businessCallId) {
        CallRecordingResponse recording = recordingService.getByBusinessCallId(businessCallId);
        return recording.getMediaId() == null ? List.of() : List.of(OpenApiRecordingResponse.from(recording));
    }

    public OpenApiTranscriptResponse transcript(String businessCallId) {
        AiCallTranscriptResponse transcript = speechService.callTranscriptByBusinessCallId(businessCallId);
        if (transcript == null) {
            throw new ServiceException("通话转写记录不存在");
        }
        return OpenApiTranscriptResponse.from(transcript);
    }
}
