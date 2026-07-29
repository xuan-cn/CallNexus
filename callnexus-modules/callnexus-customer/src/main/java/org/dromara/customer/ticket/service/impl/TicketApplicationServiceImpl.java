package org.dromara.customer.ticket.service.impl;

import lombok.RequiredArgsConstructor;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.core.domain.dto.StartProcessDTO;
import org.dromara.common.core.enums.BusinessStatusEnum;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.WorkflowService;
import org.dromara.customer.form.domain.FormBusinessType;
import org.dromara.customer.form.domain.FormTemplate;
import org.dromara.customer.form.mapper.FormTemplateMapper;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class TicketApplicationServiceImpl implements TicketApplicationService {
    private static final DateTimeFormatter TICKET_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final TicketMapper ticketMapper;
    private final FormTemplateMapper formTemplateMapper;
    private final DynamicFormSubmissionService formSubmissionService;
    private final CallBusinessAssociationService callBusinessAssociationService;
    private final ObjectProvider<WorkflowService> workflowServiceProvider;

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
        if (ticket == null) throw new ServiceException("工单不存在");
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
        FormTemplate template = requireTicketTemplate(request.getTemplateId());
        ticket.setWorkflowCode(normalizeWorkflowCode(template.getWorkflowCode()));
        ticket.setProcessStatus(BusinessStatusEnum.DRAFT.getStatus());
        ticketMapper.insert(ticket);
        formSubmissionService.validateAndSave(request.getTemplateId(), FormBusinessType.TICKET, ticket.getId(), request.getFormData());
        callBusinessAssociationService.associateTicket(request.getSourceCallId(), ticket.getId(), request.getCustomerId());
        return ticket.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submit(Long id) {
        Ticket ticket = requireTicket(id);
        if (ticket.getTicketStatus() != TicketStatus.OPEN) {
            throw new ServiceException("只有待处理工单可以提交流程");
        }
        if (ticket.getWorkflowCode() == null) {
            throw new ServiceException("当前工单模板未绑定工作流程");
        }
        WorkflowService workflowService = requireWorkflowService();
        Long existingInstanceId = workflowService.getInstanceIdByBusinessId(ticket.getId().toString());
        if (existingInstanceId != null) {
            throw new ServiceException("当前工单已经存在流程实例，请勿重复提交");
        }

        ticket.setTicketStatus(TicketStatus.PROCESSING);
        ticket.setProcessStatus(BusinessStatusEnum.WAITING.getStatus());
        ticket.setSubmittedAt(new Date());
        ticketMapper.updateById(ticket);

        StartProcessDTO startProcess = new StartProcessDTO();
        startProcess.setBusinessId(ticket.getId().toString());
        startProcess.setFlowCode(ticket.getWorkflowCode());
        startProcess.setVariables(buildWorkflowVariables(ticket));
        startProcess.getBizExt().setBusinessCode(ticket.getTicketNo());
        startProcess.getBizExt().setBusinessTitle("工单 " + ticket.getTicketNo());
        if (!workflowService.startCompleteTask(startProcess)) {
            throw new ServiceException("工单流程启动失败");
        }

        Long processInstanceId = workflowService.getInstanceIdByBusinessId(ticket.getId().toString());
        if (processInstanceId == null) {
            throw new ServiceException("工单流程已提交，但未找到流程实例");
        }
        String processStatus = workflowService.getBusinessStatus(ticket.getId().toString());
        if (BusinessStatusEnum.DRAFT.getStatus().equals(processStatus)) {
            throw new ServiceException("工单流程仍停留在提交节点，请检查首个中间节点是否配置为申请人节点");
        }

        Ticket update = new Ticket();
        update.setId(ticket.getId());
        update.setFlowInstanceId(processInstanceId);
        update.setProcessStatus(processStatus);
        ticketMapper.updateById(update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void close(Long id) {
        Ticket ticket = requireTicket(id);
        if (ticket.getTicketStatus() != TicketStatus.RESOLVED) {
            throw new ServiceException("只有已解决工单可以关闭");
        }
        ticket.setTicketStatus(TicketStatus.CLOSED);
        ticket.setClosedAt(new Date());
        ticketMapper.updateById(ticket);
    }

    private String createTicketNo() {
        return "TK" + LocalDateTime.now().format(TICKET_TIME_FORMAT) + ThreadLocalRandom.current().nextInt(1000, 10000);
    }

    private Ticket requireTicket(Long id) {
        Ticket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new ServiceException("工单不存在");
        }
        return ticket;
    }

    private FormTemplate requireTicketTemplate(Long templateId) {
        if (templateId == null) {
            throw new ServiceException("请选择工单模板");
        }
        FormTemplate template = formTemplateMapper.selectById(templateId);
        if (template == null || template.getBusinessType() != FormBusinessType.TICKET || !Boolean.TRUE.equals(template.getEnabled())) {
            throw new ServiceException("工单模板不存在或未启用");
        }
        return template;
    }

    private WorkflowService requireWorkflowService() {
        WorkflowService workflowService = workflowServiceProvider.getIfAvailable();
        if (workflowService == null) {
            throw new ServiceException("工作流功能未启用");
        }
        return workflowService;
    }

    private String normalizeWorkflowCode(String workflowCode) {
        return workflowCode == null || workflowCode.isBlank() ? null : workflowCode.trim();
    }

    private Map<String, Object> buildWorkflowVariables(Ticket ticket) {
        Map<String, Object> entity = new HashMap<>();
        entity.put("ticketId", ticket.getId());
        entity.put("ticketNo", ticket.getTicketNo());
        entity.put("customerId", ticket.getCustomerId());
        entity.put("callerNumber", ticket.getCallerNumber());
        entity.put("sourceCallId", ticket.getSourceCallId());
        entity.put("templateId", ticket.getTemplateId());
        Map<String, Object> variables = new HashMap<>();
        variables.put("entity", entity);
        return variables;
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
        response.setWorkflowCode(ticket.getWorkflowCode());
        response.setProcessStatus(ticket.getProcessStatus());
        response.setFlowInstanceId(ticket.getFlowInstanceId());
        response.setCurrentNodeCode(ticket.getCurrentNodeCode());
        response.setCurrentNodeName(ticket.getCurrentNodeName());
        response.setSubmittedAt(toLocalDateTime(ticket.getSubmittedAt()));
        response.setResolvedAt(toLocalDateTime(ticket.getResolvedAt()));
        response.setClosedAt(toLocalDateTime(ticket.getClosedAt()));
        response.setCreateTime(toLocalDateTime(ticket.getCreateTime()));
        return response;
    }

    private LocalDateTime toLocalDateTime(Date date) {
        return date == null ? null : date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }
}
