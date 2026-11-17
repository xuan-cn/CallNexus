package org.dromara.ai.service;

import org.dromara.ai.domain.response.AiWorkflowTicketPreview;

import java.util.Map;

public interface AiWorkflowTicketService {
    Result register(Long templateId, Map<String, Object> formData, String businessCallId, Long agentId, boolean test);

    record Result(Long ticketId, String validationError, AiWorkflowTicketPreview preview) {
        public Result(Long ticketId, String validationError) {
            this(ticketId, validationError, null);
        }
    }
}
