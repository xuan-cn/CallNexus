package org.dromara.ai.service;

import org.dromara.ai.domain.request.AiWorkflowTestInputRequest;
import org.dromara.ai.domain.request.AiWorkflowTestStartRequest;
import org.dromara.ai.domain.response.AiWorkflowTestExecutionResponse;
import org.dromara.ai.domain.response.AiWorkflowVoiceExecutionResponse;
import org.dromara.ai.domain.response.AiChatTurnResult;

import java.util.Map;
import java.util.Optional;

public interface AiWorkflowRuntimeService {
    AiWorkflowTestExecutionResponse startTest(Long workflowId, AiWorkflowTestStartRequest request);
    AiWorkflowTestExecutionResponse input(String executionId, AiWorkflowTestInputRequest request);
    AiWorkflowTestExecutionResponse execution(String executionId);
    Optional<AiWorkflowVoiceExecutionResponse> startVoice(Long agentId, String businessCallId, String sceneType,
                                                          Map<String, Object> variables);
    AiWorkflowVoiceExecutionResponse voiceInput(String executionId, String inputId, String text);
    void voiceStreamCompleted(String executionId, AiChatTurnResult result);
    AiWorkflowVoiceExecutionResponse voiceTtsCompleted(String executionId);
    void terminate(String executionId, String reason);
}
