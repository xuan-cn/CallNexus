package org.dromara.ai.service;

import org.dromara.ai.service.model.AiTicketTemplateContext;

public interface AiTicketBusinessContextProvider {
    AiTicketTemplateContext load(Long ticketTemplateId, String callerNumber);
    boolean hasFormalTicket(String businessCallId);
}
