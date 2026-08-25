package org.dromara.customer.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.service.AiIntentTicketActionService;
import org.dromara.call.domain.CallSession;
import org.dromara.call.mapper.CallSessionMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.customer.ticket.domain.request.CreateTicketRequest;
import org.dromara.customer.ticket.service.TicketApplicationService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiIntentTicketActionServiceImpl implements AiIntentTicketActionService {
    private final CallSessionMapper callSessionMapper;
    private final TicketApplicationService ticketService;

    @Override
    public Long create(String businessCallId, Long templateId, boolean submitAfterCreate) {
        CallSession call = callSessionMapper.selectOne(new LambdaQueryWrapper<CallSession>()
            .eq(CallSession::getBusinessCallId, businessCallId)
            .last("LIMIT 1"));
        if (call == null) throw new ServiceException("AI 通话记录不存在，无法创建工单");
        CreateTicketRequest request = new CreateTicketRequest();
        request.setCustomerId(call.getCustomerId());
        request.setCallerNumber(call.getDirection() != null && call.getDirection().startsWith("OUTBOUND")
            ? call.getCalledNumber() : call.getCallerNumber());
        request.setSourceCallId(businessCallId);
        request.setTemplateId(templateId);
        Long ticketId = ticketService.create(request);
        if (submitAfterCreate) ticketService.submit(ticketId);
        return ticketId;
    }
}
