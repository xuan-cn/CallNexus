package org.dromara.ai.service;

import org.dromara.ai.domain.request.AiAgentWorkflowBindingRequest;
import org.dromara.ai.domain.request.AiWorkflowDraftRequest;
import org.dromara.ai.domain.request.AiWorkflowRequest;
import org.dromara.ai.domain.response.AiAgentWorkflowBindingResponse;
import org.dromara.ai.domain.response.AiWorkflowResponse;
import org.dromara.ai.domain.response.AiWorkflowValidationResponse;
import org.dromara.ai.domain.response.AiWorkflowVersionResponse;

import java.util.List;

public interface AiWorkflowApplicationService {
    List<AiWorkflowResponse> workflows();
    AiWorkflowResponse workflow(Long id);
    Long create(AiWorkflowRequest request);
    void update(Long id, AiWorkflowRequest request);
    void delete(Long id);
    void setEnabled(Long id, boolean enabled);
    List<AiWorkflowVersionResponse> versions(Long workflowId);
    AiWorkflowVersionResponse draft(Long workflowId);
    Long saveDraft(Long workflowId, AiWorkflowDraftRequest request);
    AiWorkflowValidationResponse validateDraft(Long workflowId);
    AiWorkflowVersionResponse publish(Long workflowId);
    List<AiAgentWorkflowBindingResponse> agentBindings(Long agentId);
    void saveAgentBinding(Long agentId, AiAgentWorkflowBindingRequest request);
    void deleteAgentBinding(Long agentId, String sceneType);
}
