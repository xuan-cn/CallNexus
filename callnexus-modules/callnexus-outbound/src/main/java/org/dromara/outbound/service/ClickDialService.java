package org.dromara.outbound.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.agent.domain.AgentPresenceStatus;
import org.dromara.agent.domain.response.CurrentAgentResponse;
import org.dromara.agent.service.CurrentAgentSessionService;
import org.dromara.call.domain.CallOriginateContext;
import org.dromara.call.domain.response.CallControlResponse;
import org.dromara.call.service.CallBusinessAssociationService;
import org.dromara.call.service.CallControlApplicationService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.customer.customer.domain.Customer;
import org.dromara.customer.customer.domain.CustomerAssignment;
import org.dromara.customer.customer.domain.CustomerPhone;
import org.dromara.customer.customer.domain.response.CustomerPhoneResponse;
import org.dromara.customer.customer.mapper.CustomerAssignmentMapper;
import org.dromara.customer.customer.mapper.CustomerMapper;
import org.dromara.customer.customer.mapper.CustomerPhoneMapper;
import org.dromara.customer.customer.service.CustomerApplicationService;
import org.dromara.outbound.domain.CustomerDialAttempt;
import org.dromara.outbound.domain.request.ClickDialCustomerQuery;
import org.dromara.outbound.domain.request.ClickDialRequest;
import org.dromara.outbound.domain.request.CompleteClickDialRequest;
import org.dromara.outbound.domain.response.ClickDialCallResponse;
import org.dromara.outbound.domain.response.ClickDialCustomerResponse;
import org.dromara.outbound.domain.response.ClickDialSummaryResponse;
import org.dromara.outbound.domain.response.CustomerDialAttemptResponse;
import org.dromara.outbound.domain.response.OutboundBlacklistMatch;
import org.dromara.outbound.mapper.CustomerDialAttemptMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ClickDialService {
    private static final Set<String> DIAL_STATUSES = Set.of("PROCESSING", "FOLLOW_UP", "COMPLETED");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CustomerDialAttemptMapper attemptMapper;
    private final CustomerAssignmentMapper assignmentMapper;
    private final CustomerMapper customerMapper;
    private final CustomerPhoneMapper phoneMapper;
    private final CurrentAgentSessionService agentSessionService;
    private final CallControlApplicationService callControlService;
    private final CallBusinessAssociationService callBusinessAssociationService;
    private final CustomerApplicationService customerService;
    private final OutboundBlacklistChecker blacklistChecker;

    public TableDataInfo<ClickDialCustomerResponse> page(ClickDialCustomerQuery query) {
        CurrentAgentResponse agent = requireConfiguredAgent();
        ClickDialCustomerQuery safeQuery = query == null ? new ClickDialCustomerQuery() : query;
        int pageNum = safeQuery.getPageNum() == null || safeQuery.getPageNum() < 1 ? 1 : safeQuery.getPageNum();
        int pageSize = safeQuery.getPageSize() == null || safeQuery.getPageSize() < 1
            ? 10 : Math.min(safeQuery.getPageSize(), 200);
        IPage<ClickDialCustomerResponse> page = attemptMapper.selectCustomerPage(
            new Page<>(pageNum, pageSize), agent.getAgentId(), safeQuery);
        attachPhones(page.getRecords());
        return new TableDataInfo<>(page.getRecords(), page.getTotal());
    }

    public ClickDialSummaryResponse summary() {
        CurrentAgentResponse agent = requireConfiguredAgent();
        ClickDialSummaryResponse response = attemptMapper.selectSummary(agent.getAgentId());
        return response == null ? new ClickDialSummaryResponse() : response;
    }

    @Transactional(rollbackFor = Exception.class)
    public ClickDialCallResponse dial(Long assignmentId, ClickDialRequest request) {
        CurrentAgentResponse agent = requireDialableAgent();
        CustomerAssignment assignment = requireOwnedAssignment(assignmentId, agent.getAgentId());
        Customer customer = customerMapper.selectById(assignment.getCustomerId());
        if (customer == null) throw new ServiceException("客户不存在");
        DialPhone dialPhone = resolvePhone(customer, request == null ? null : request.getPhoneId());
        OutboundBlacklistMatch blacklist = blacklistChecker.check(null, dialPhone.number());
        if (blacklist != null) throw new ServiceException("该电话号码已命中外呼黑名单");
        long activeAttempts = attemptMapper.selectCount(new LambdaQueryWrapper<CustomerDialAttempt>()
            .eq(CustomerDialAttempt::getAgentId, agent.getAgentId())
            .eq(CustomerDialAttempt::getStatus, "DIALING"));
        if (activeAttempts > 0) throw new ServiceException("当前坐席已有正在拨打的客户，请稍后再试");

        LocalDateTime now = LocalDateTime.now();
        int attemptNo = (assignment.getAttemptCount() == null ? 0 : assignment.getAttemptCount()) + 1;
        String businessCallId = UUID.randomUUID().toString();
        CustomerDialAttempt attempt = new CustomerDialAttempt();
        attempt.setAssignmentId(assignmentId);
        attempt.setCustomerId(assignment.getCustomerId());
        attempt.setAgentId(agent.getAgentId());
        attempt.setUserId(LoginHelper.getUserId());
        attempt.setPhoneId(dialPhone.id());
        attempt.setPhoneNumber(dialPhone.number());
        attempt.setAttemptNo(attemptNo);
        attempt.setBusinessCallId(businessCallId);
        attempt.setStatus("DIALING");
        attempt.setStartedAt(now);
        attempt.setDurationSeconds(0);
        attempt.setBillableSeconds(0);
        attemptMapper.insert(attempt);

        assignmentMapper.update(null, new LambdaUpdateWrapper<CustomerAssignment>()
            .eq(CustomerAssignment::getId, assignmentId)
            .eq(CustomerAssignment::getAgentId, agent.getAgentId())
            .eq(CustomerAssignment::getEnabled, true)
            .set(CustomerAssignment::getDialStatus, "PROCESSING")
            .set(CustomerAssignment::getAttemptCount, attemptNo)
            .set(CustomerAssignment::getLastCalledAt, now)
            .set(CustomerAssignment::getLastResultCode, null)
            .set(CustomerAssignment::getLastResultRemark, null)
            .set(CustomerAssignment::getNextFollowUpAt, null)
            .set(CustomerAssignment::getCompletedAt, null));

        CallControlResponse call;
        try {
            call = callControlService.originate(dialPhone.number(),
                new CallOriginateContext(businessCallId, assignment.getCustomerId(), null, null,
                    null, assignment.getSkillGroupId(), null));
        } catch (RuntimeException exception) {
            attemptMapper.update(null, new LambdaUpdateWrapper<CustomerDialAttempt>()
                .eq(CustomerDialAttempt::getId, attempt.getId())
                .set(CustomerDialAttempt::getStatus, "ENDED")
                .set(CustomerDialAttempt::getEndedAt, LocalDateTime.now())
                .set(CustomerDialAttempt::getSuggestedResultCode, "OTHER")
                .set(CustomerDialAttempt::getHangupCause, "ORIGINATE_FAILED"));
            assignmentMapper.update(null, new LambdaUpdateWrapper<CustomerAssignment>()
                .eq(CustomerAssignment::getId, assignmentId)
                .set(CustomerAssignment::getLastResultCode, "OTHER")
                .set(CustomerAssignment::getLastResultRemark, "发起拨号失败"));
            throw exception;
        }
        callBusinessAssociationService.associateCustomer(call.getBusinessCallId(), assignment.getCustomerId());
        ClickDialCallResponse response = new ClickDialCallResponse();
        response.setAttemptId(attempt.getId());
        response.setAssignmentId(assignmentId);
        response.setBusinessCallId(call.getBusinessCallId());
        response.setFrontendCallId(call.getCallId());
        response.setPhoneNumber(dialPhone.number());
        response.setStatus(call.getStatus());
        return response;
    }

    @Transactional(rollbackFor = Exception.class)
    public void complete(Long assignmentId, CompleteClickDialRequest request) {
        CurrentAgentResponse agent = requireConfiguredAgent();
        CustomerAssignment assignment = requireOwnedAssignment(assignmentId, agent.getAgentId());
        if (!DIAL_STATUSES.contains(request.getDialStatus())) throw new ServiceException("处理状态无效");
        if ("FOLLOW_UP".equals(request.getDialStatus())) {
            if (request.getNextFollowUpAt() == null) throw new ServiceException("需要跟进时必须设置下次跟进时间");
            if (!request.getNextFollowUpAt().isAfter(LocalDateTime.now())) throw new ServiceException("下次跟进时间必须晚于当前时间");
        }
        CustomerDialAttempt attempt = attemptMapper.selectOne(new LambdaQueryWrapper<CustomerDialAttempt>()
            .eq(CustomerDialAttempt::getBusinessCallId, request.getBusinessCallId())
            .eq(CustomerDialAttempt::getAssignmentId, assignmentId)
            .eq(CustomerDialAttempt::getAgentId, agent.getAgentId())
            .last("LIMIT 1"));
        if (attempt == null) throw new ServiceException("拨打记录不存在或无权处理");
        attemptMapper.update(null, new LambdaUpdateWrapper<CustomerDialAttempt>()
            .eq(CustomerDialAttempt::getId, attempt.getId())
            .set(CustomerDialAttempt::getResultCode, request.getResultCode())
            .set(CustomerDialAttempt::getResultRemark, request.getResultRemark()));
        assignmentMapper.update(null, new LambdaUpdateWrapper<CustomerAssignment>()
            .eq(CustomerAssignment::getId, assignmentId)
            .eq(CustomerAssignment::getAgentId, agent.getAgentId())
            .eq(CustomerAssignment::getEnabled, true)
            .set(CustomerAssignment::getDialStatus, request.getDialStatus())
            .set(CustomerAssignment::getLastResultCode, request.getResultCode())
            .set(CustomerAssignment::getLastResultRemark, request.getResultRemark())
            .set(CustomerAssignment::getNextFollowUpAt,
                "FOLLOW_UP".equals(request.getDialStatus()) ? request.getNextFollowUpAt() : null)
            .set(CustomerAssignment::getCompletedAt,
                "COMPLETED".equals(request.getDialStatus()) ? LocalDateTime.now() : null));
        customerService.recordClickDialResult(assignment.getCustomerId(), attempt.getId(), buildFollowUpContent(request));
    }

    public List<CustomerDialAttemptResponse> attempts(Long assignmentId) {
        CurrentAgentResponse agent = requireConfiguredAgent();
        requireOwnedAssignment(assignmentId, agent.getAgentId());
        return attemptMapper.selectList(new LambdaQueryWrapper<CustomerDialAttempt>()
                .eq(CustomerDialAttempt::getAssignmentId, assignmentId)
                .eq(CustomerDialAttempt::getAgentId, agent.getAgentId())
                .orderByDesc(CustomerDialAttempt::getStartedAt)
                .last("LIMIT 100"))
            .stream().map(this::toAttemptResponse).toList();
    }

    private void attachPhones(List<ClickDialCustomerResponse> records) {
        if (records.isEmpty()) return;
        List<Long> customerIds = records.stream().map(ClickDialCustomerResponse::getCustomerId).toList();
        Map<Long, List<CustomerPhoneResponse>> phones = phoneMapper.selectList(new LambdaQueryWrapper<CustomerPhone>()
                .in(CustomerPhone::getCustomerId, customerIds)
                .eq(CustomerPhone::getEnabled, true)
                .orderByDesc(CustomerPhone::getPrimaryFlag)
                .orderByAsc(CustomerPhone::getSortOrder))
            .stream().map(phone -> Map.entry(phone.getCustomerId(), toPhoneResponse(phone)))
            .collect(Collectors.groupingBy(Map.Entry::getKey,
                Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        records.forEach(record -> record.setPhones(phones.getOrDefault(record.getCustomerId(), List.of())));
    }

    private DialPhone resolvePhone(Customer customer, Long phoneId) {
        if (phoneId != null) {
            CustomerPhone phone = phoneMapper.selectOne(new LambdaQueryWrapper<CustomerPhone>()
                .eq(CustomerPhone::getId, phoneId)
                .eq(CustomerPhone::getCustomerId, customer.getId())
                .eq(CustomerPhone::getEnabled, true));
            if (phone == null) throw new ServiceException("客户号码不存在或已停用");
            String number = phone.getNormalizedPhone() == null || phone.getNormalizedPhone().isBlank()
                ? phone.getPhoneNumber() : phone.getNormalizedPhone();
            return new DialPhone(phone.getId(), number);
        }
        if (customer.getPrimaryPhone() == null || customer.getPrimaryPhone().isBlank()) {
            throw new ServiceException("客户没有可拨打号码");
        }
        return new DialPhone(null, customer.getPrimaryPhone());
    }

    private CustomerAssignment requireOwnedAssignment(Long assignmentId, Long agentId) {
        CustomerAssignment assignment = assignmentMapper.selectOne(new LambdaQueryWrapper<CustomerAssignment>()
            .eq(CustomerAssignment::getId, assignmentId)
            .eq(CustomerAssignment::getAgentId, agentId)
            .eq(CustomerAssignment::getEnabled, true)
            .last("LIMIT 1"));
        if (assignment == null) throw new ServiceException("客户未分配给当前坐席或归属已失效");
        return assignment;
    }

    private CurrentAgentResponse requireConfiguredAgent() {
        CurrentAgentResponse agent = agentSessionService.current();
        if (!agent.isConfigured() || agent.getAgentId() == null) throw new ServiceException("当前用户尚未绑定坐席");
        return agent;
    }

    private CurrentAgentResponse requireDialableAgent() {
        CurrentAgentResponse agent = requireConfiguredAgent();
        if (agent.getStatus() == AgentPresenceStatus.OFFLINE) throw new ServiceException("坐席未签入，请先签入");
        if (agent.getStatus() == AgentPresenceStatus.BUSY || agent.getActiveCallId() != null) {
            throw new ServiceException("坐席当前正在通话，不能再次拨号");
        }
        return agent;
    }

    private String buildFollowUpContent(CompleteClickDialRequest request) {
        StringBuilder content = new StringBuilder("点击拨号结果：").append(resultLabel(request.getResultCode()));
        if (request.getResultRemark() != null && !request.getResultRemark().isBlank()) {
            content.append("\n处理备注：").append(request.getResultRemark().trim());
        }
        if (request.getNextFollowUpAt() != null) {
            content.append("\n下次跟进：").append(request.getNextFollowUpAt().format(TIME_FORMATTER));
        }
        return content.toString();
    }

    private String resultLabel(String code) {
        return switch (code) {
            case "CONNECTED" -> "已接通";
            case "NO_ANSWER" -> "无人接听";
            case "BUSY" -> "客户忙";
            case "INVALID_NUMBER" -> "号码无效";
            case "NOT_INTERESTED" -> "无意向";
            case "FOLLOW_UP" -> "需要跟进";
            default -> "其他";
        };
    }

    private CustomerPhoneResponse toPhoneResponse(CustomerPhone phone) {
        CustomerPhoneResponse response = new CustomerPhoneResponse();
        response.setId(phone.getId());
        response.setPhoneNumber(phone.getPhoneNumber());
        response.setNormalizedPhone(phone.getNormalizedPhone());
        response.setPhoneType(phone.getPhoneType());
        response.setPhoneLabel(phone.getPhoneLabel());
        response.setPrimaryFlag(phone.getPrimaryFlag());
        response.setEnabled(phone.getEnabled());
        response.setSortOrder(phone.getSortOrder());
        return response;
    }

    private CustomerDialAttemptResponse toAttemptResponse(CustomerDialAttempt attempt) {
        CustomerDialAttemptResponse response = new CustomerDialAttemptResponse();
        response.setId(attempt.getId());
        response.setPhoneNumber(attempt.getPhoneNumber());
        response.setAttemptNo(attempt.getAttemptNo());
        response.setBusinessCallId(attempt.getBusinessCallId());
        response.setStatus(attempt.getStatus());
        response.setResultCode(attempt.getResultCode());
        response.setResultRemark(attempt.getResultRemark());
        response.setSuggestedResultCode(attempt.getSuggestedResultCode());
        response.setStartedAt(attempt.getStartedAt());
        response.setAnsweredAt(attempt.getAnsweredAt());
        response.setEndedAt(attempt.getEndedAt());
        response.setDurationSeconds(attempt.getDurationSeconds());
        response.setBillableSeconds(attempt.getBillableSeconds());
        response.setHangupCause(attempt.getHangupCause());
        return response;
    }

    private record DialPhone(Long id, String number) {
    }
}
