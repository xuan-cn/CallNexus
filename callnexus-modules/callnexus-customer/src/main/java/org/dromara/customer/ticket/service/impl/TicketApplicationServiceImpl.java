package org.dromara.customer.ticket.service.impl;

import lombok.RequiredArgsConstructor;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.customer.form.domain.FormBusinessType;
import org.dromara.customer.form.service.DynamicFormSubmissionService;
import org.dromara.customer.ticket.domain.Ticket;
import org.dromara.customer.ticket.domain.TicketStatus;
import org.dromara.customer.ticket.domain.request.CreateTicketRequest;
import org.dromara.customer.ticket.domain.request.TicketPageQuery;
import org.dromara.customer.ticket.domain.response.TicketResponse;
import org.dromara.customer.ticket.mapper.TicketMapper;
import org.dromara.customer.ticket.service.TicketApplicationService;
import org.dromara.call.service.CallBusinessAssociationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class TicketApplicationServiceImpl implements TicketApplicationService {
    private static final DateTimeFormatter TICKET_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final TicketMapper ticketMapper;
    private final DynamicFormSubmissionService formSubmissionService;
    private final CallBusinessAssociationService callBusinessAssociationService;

    @Override
    public TableDataInfo<TicketResponse> page(TicketPageQuery query, PageQuery pageQuery) {
        Page<Ticket> page = ticketMapper.selectPage(pageQuery.build(), new LambdaQueryWrapper<Ticket>()
            .like(query.getTicketNo() != null && !query.getTicketNo().isBlank(), Ticket::getTicketNo, query.getTicketNo())
            .like(query.getCallerNumber() != null && !query.getCallerNumber().isBlank(), Ticket::getCallerNumber, query.getCallerNumber())
            .eq(query.getTicketStatus() != null, Ticket::getTicketStatus, query.getTicketStatus())
            .orderByDesc(Ticket::getCreateTime));
        return new TableDataInfo<>(page.getRecords().stream().map(this::toResponse).toList(), page.getTotal());
    }

    @Override
    public TicketResponse get(Long id) {
        Ticket ticket = ticketMapper.selectById(id);
        if (ticket == null) throw new ServiceException("TICKET_NOT_FOUND");
        TicketResponse response = toResponse(ticket);
        response.setFormData(formSubmissionService.getFormData(FormBusinessType.TICKET, id));
        return response;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(CreateTicketRequest request) {
        Ticket ticket = new Ticket();
        ticket.setTicketNo(createTicketNo());
        ticket.setTicketStatus(TicketStatus.OPEN);
        ticket.setCustomerId(request.getCustomerId());
        ticket.setCallerNumber(request.getCallerNumber());
        ticket.setSourceCallId(request.getSourceCallId());
        ticket.setTemplateId(request.getTemplateId());
        ticketMapper.insert(ticket);
        formSubmissionService.validateAndSave(request.getTemplateId(), FormBusinessType.TICKET, ticket.getId(), request.getFormData());
        callBusinessAssociationService.associateTicket(request.getSourceCallId(), ticket.getId(), request.getCustomerId());
        return ticket.getId();
    }

    private String createTicketNo() {
        return "TK" + LocalDateTime.now().format(TICKET_TIME_FORMAT) + ThreadLocalRandom.current().nextInt(1000, 10000);
    }

    private TicketResponse toResponse(Ticket ticket) {
        TicketResponse response = new TicketResponse();
        response.setId(ticket.getId());
        response.setTicketNo(ticket.getTicketNo());
        response.setTicketStatus(ticket.getTicketStatus());
        response.setCustomerId(ticket.getCustomerId());
        response.setCallerNumber(ticket.getCallerNumber());
        response.setSourceCallId(ticket.getSourceCallId());
        response.setTemplateId(ticket.getTemplateId());
        response.setCreateTime(ticket.getCreateTime().toInstant()
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime());
        return response;
    }
}
