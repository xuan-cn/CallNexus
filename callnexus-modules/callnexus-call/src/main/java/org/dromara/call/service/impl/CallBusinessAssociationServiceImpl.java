package org.dromara.call.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.call.domain.CallSession;
import org.dromara.call.mapper.CallSessionMapper;
import org.dromara.call.service.CallBusinessAssociationService;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CallBusinessAssociationServiceImpl implements CallBusinessAssociationService {

    private final CallSessionMapper sessionMapper;

    @Override
    public Long findHandlingAgentId(String businessCallId) {
        if (StringUtils.isBlank(businessCallId)) return null;
        CallSession session = sessionMapper.selectOne(new LambdaQueryWrapper<CallSession>()
            .eq(CallSession::getBusinessCallId, businessCallId)
            .orderByDesc(CallSession::getCreateTime)
            .last("limit 1"));
        if (session == null) return null;
        return session.getOwnerAgentId() != null ? session.getOwnerAgentId() : session.getAgentId();
    }

    @Override
    public void associateCustomer(String businessCallId, Long customerId) {
        if (StringUtils.isBlank(businessCallId) || customerId == null) return;
        sessionMapper.update(null, new LambdaUpdateWrapper<CallSession>()
            .eq(CallSession::getBusinessCallId, businessCallId)
            .set(CallSession::getCustomerId, customerId));
    }

    @Override
    public void associateTicket(String businessCallId, Long ticketId, Long customerId) {
        if (StringUtils.isBlank(businessCallId) || ticketId == null) return;
        LambdaUpdateWrapper<CallSession> update = new LambdaUpdateWrapper<CallSession>()
            .eq(CallSession::getBusinessCallId, businessCallId)
            .set(CallSession::getTicketId, ticketId);
        if (customerId != null) update.set(CallSession::getCustomerId, customerId);
        sessionMapper.update(null, update);
    }
}
