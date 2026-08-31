package org.dromara.customer.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.service.AiIntentTicketActionService;
import org.dromara.ai.service.AiTicketConversionService;
import org.dromara.call.domain.CallSession;
import org.dromara.call.mapper.CallSessionMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.customer.ticket.domain.Ticket;
import org.dromara.customer.ticket.mapper.TicketMapper;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiIntentTicketActionServiceImpl implements AiIntentTicketActionService {
    private final CallSessionMapper callSessionMapper;
    private final TicketMapper ticketMapper;
    private final AiTicketConversionService conversionService;

    @Override
    public Long create(String businessCallId, Long templateId, boolean submitAfterCreate) {
        CallSession call = callSessionMapper.selectOne(new LambdaQueryWrapper<CallSession>()
            .eq(CallSession::getBusinessCallId, businessCallId)
            .last("LIMIT 1"));
        if (call == null) throw new ServiceException("AI 通话记录不存在，无法创建工单");
        Ticket existing = ticketMapper.selectOne(new LambdaQueryWrapper<Ticket>()
            .eq(Ticket::getSourceCallId, businessCallId)
            .orderByAsc(Ticket::getCreateTime).last("LIMIT 1"));
        if (existing != null) return existing.getId();
        String callerNumber = call.getDirection() != null && call.getDirection().startsWith("OUTBOUND")
            ? call.getCalledNumber() : call.getCallerNumber();
        return conversionService.convert(new AiTicketConversionService.Command(null, call.getCustomerId(), callerNumber,
            businessCallId, templateId, null, java.util.Map.of(), "INTENT", null, null,
            submitAfterCreate ? "SUBMIT" : "CREATE_ONLY"));
    }
}
