package org.dromara.customer.customer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.call.service.BusinessAssociationQueryService;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.customer.customer.domain.Customer;
import org.dromara.customer.customer.mapper.CustomerMapper;
import org.dromara.customer.ticket.domain.Ticket;
import org.dromara.customer.ticket.mapper.TicketMapper;
import org.springframework.stereotype.Service;

/**
 * 业务关联按号码回查实现。
 *
 * <p>通话详情优先使用显式关联（坐席创建客户/工单时写入 business_call_id），
 * 显式关联为空时按号码回查历史客户和工单，由 call 模块通过 {@link BusinessAssociationQueryService} 调用。
 */
@Service
@RequiredArgsConstructor
public class BusinessAssociationQueryServiceImpl implements BusinessAssociationQueryService {
    private final CustomerMapper customerMapper;
    private final TicketMapper ticketMapper;

    @Override
    public Long findCustomerIdByPhone(String phone) {
        if (StringUtils.isBlank(phone)) return null;
        return TenantHelper.ignore(() -> {
            Customer customer = customerMapper.selectOne(new LambdaQueryWrapper<Customer>()
                .eq(Customer::getPrimaryPhone, phone)
                .last("limit 1"));
            return customer == null ? null : customer.getId();
        });
    }

    @Override
    public Long findLatestTicketIdByCallerNumber(String callerNumber) {
        if (StringUtils.isBlank(callerNumber)) return null;
        return TenantHelper.ignore(() -> {
            Ticket ticket = ticketMapper.selectOne(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getCallerNumber, callerNumber)
                .orderByDesc(Ticket::getCreateTime)
                .last("limit 1"));
            return ticket == null ? null : ticket.getId();
        });
    }
}
