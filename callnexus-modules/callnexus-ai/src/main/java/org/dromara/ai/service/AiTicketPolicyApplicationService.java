package org.dromara.ai.service;

import org.dromara.ai.domain.request.AiTicketPolicyRequest;
import org.dromara.ai.domain.request.AiTicketPromptRequest;
import org.dromara.ai.domain.response.AiTicketPolicyResponse;
import org.dromara.ai.domain.response.AiTicketPromptResponse;
import org.dromara.ai.domain.response.AiTicketPromptValidationResponse;
import org.dromara.ai.domain.response.AiTicketPromptVersionResponse;

import java.util.List;

public interface AiTicketPolicyApplicationService {
    AiTicketPolicyResponse policy(Long agentId);
    Long savePolicy(Long agentId, AiTicketPolicyRequest request);
    AiTicketPromptResponse prompt(Long agentId);
    Long savePromptDraft(Long agentId, AiTicketPromptRequest request);
    AiTicketPromptValidationResponse validatePrompt(Long agentId, AiTicketPromptRequest request);
    void publishPrompt(Long agentId);
    Long restoreDefaultPrompt(Long agentId);
    List<AiTicketPromptVersionResponse> promptVersions(Long agentId);
}
