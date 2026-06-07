package org.dromara.customer.ticket.service;

import org.dromara.customer.ticket.domain.request.CreateTicketRequest;
import org.dromara.customer.ticket.domain.request.TicketPageQuery;
import org.dromara.customer.ticket.domain.response.TicketResponse;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;

public interface TicketApplicationService {
    TableDataInfo<TicketResponse> page(TicketPageQuery query, PageQuery pageQuery);
    TicketResponse get(Long id);
    Long create(CreateTicketRequest request);
}
