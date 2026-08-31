package org.dromara.ai.service;

import java.util.Map;

public interface AiTicketConversionService {
    Long convert(Command command);

    Long findDuplicateTicket(Long customerId, String callerNumber, Long ticketTemplateId,
                             Integer windowHours);

    record Command(Long draftId, Long customerId, String callerNumber, String sourceCallId,
                   Long ticketTemplateId, Long aiAgentId, Map<String, Object> formData,
                   String conversionMode, Long customerTemplateId, Long defaultSkillGroupId,
                   String afterCreateAction) {
    }
}
