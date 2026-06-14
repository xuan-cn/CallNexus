package org.dromara.outbound.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.agent.domain.AgentPresenceStatus;
import org.dromara.agent.domain.response.CurrentAgentResponse;
import org.dromara.agent.service.CurrentAgentSessionService;
import org.dromara.call.domain.response.CallControlResponse;
import org.dromara.call.domain.CallOriginateContext;
import org.dromara.call.service.CallBusinessAssociationService;
import org.dromara.call.service.CallControlApplicationService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.customer.customer.domain.response.CustomerResponse;
import org.dromara.customer.customer.service.CustomerApplicationService;
import org.dromara.outbound.domain.OutboundMember;
import org.dromara.outbound.domain.OutboundTask;
import org.dromara.outbound.domain.request.CompleteOutboundMemberRequest;
import org.dromara.outbound.domain.request.OutboundTaskRequest;
import org.dromara.outbound.domain.response.OutboundMemberResponse;
import org.dromara.outbound.domain.response.OutboundTaskStatisticsResponse;
import org.dromara.outbound.domain.response.OutboundTaskResponse;
import org.dromara.outbound.mapper.OutboundMemberMapper;
import org.dromara.outbound.mapper.OutboundTaskMapper;
import org.dromara.outbound.service.OutboundTaskService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboundTaskServiceImpl implements OutboundTaskService {
    private static final Set<String> EXECUTABLE_MEMBER_STATUSES = Set.of("CLAIMED", "RETRY");
    private static final DateTimeFormatter FOLLOW_UP_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int CLAIM_LEASE_MINUTES = 15;
    private static final int DIALING_LEASE_HOURS = 2;

    private final OutboundTaskMapper taskMapper;
    private final OutboundMemberMapper memberMapper;
    private final CustomerApplicationService customerService;
    private final CurrentAgentSessionService agentSessionService;
    private final CallControlApplicationService callControlService;
    private final CallBusinessAssociationService callBusinessAssociationService;

    @Override
    public List<OutboundTaskResponse> list() {
        return taskMapper.selectList(new LambdaQueryWrapper<OutboundTask>()
                .orderByDesc(OutboundTask::getCreateTime))
            .stream().map(this::toTaskResponse).toList();
    }

    @Override
    public OutboundTaskResponse get(Long id) {
        return toTaskResponse(requireTask(id));
    }

    @Override
    public Long create(OutboundTaskRequest request) {
        OutboundTask task = new OutboundTask();
        applyTask(task, request);
        task.setTaskType("PREVIEW");
        task.setStatus("DRAFT");
        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException exception) {
            throw new ServiceException("外呼任务编码已存在");
        }
        return task.getId();
    }

    @Override
    public void update(Long id, OutboundTaskRequest request) {
        OutboundTask task = requireTask(id);
        if ("RUNNING".equals(task.getStatus())) {
            throw new ServiceException("执行中的外呼任务不能修改，请先暂停");
        }
        applyTask(task, request);
        task.setVersion(request.getVersion());
        try {
            if (taskMapper.updateById(task) == 0) throw new ServiceException("外呼任务已被其他用户修改，请刷新后重试");
        } catch (DuplicateKeyException exception) {
            throw new ServiceException("外呼任务编码已存在");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        OutboundTask task = requireTask(id);
        if ("RUNNING".equals(task.getStatus())) throw new ServiceException("执行中的外呼任务不能删除");
        memberMapper.delete(new LambdaQueryWrapper<OutboundMember>().eq(OutboundMember::getTaskId, id));
        taskMapper.deleteById(task.getId());
    }

    @Override
    public void start(Long id) {
        requireTask(id);
        if (memberMapper.selectCount(new LambdaQueryWrapper<OutboundMember>()
            .eq(OutboundMember::getTaskId, id)
            .in(OutboundMember::getStatus, "PENDING", "RETRY", "CLAIMED", "DIALING")) == 0) {
            throw new ServiceException("外呼任务没有待处理名单，无法开始");
        }
        updateTaskStatus(id, "RUNNING");
    }

    @Override
    public void pause(Long id) {
        requireTask(id);
        updateTaskStatus(id, "PAUSED");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addCustomers(Long id, List<Long> customerIds) {
        OutboundTask task = requireTask(id);
        if ("RUNNING".equals(task.getStatus())) throw new ServiceException("执行中的外呼任务不能添加名单，请先暂停");
        for (Long customerId : customerIds.stream().distinct().toList()) {
            CustomerResponse customer = customerService.get(customerId);
            OutboundMember member = new OutboundMember();
            member.setTaskId(id);
            member.setCustomerId(customerId);
            member.setCustomerName(customer.getCustomerName());
            member.setPhoneNumber(customer.getPrimaryPhone());
            member.setStatus("PENDING");
            member.setAttemptCount(0);
            try {
                memberMapper.insert(member);
            } catch (DuplicateKeyException ignored) {
                // 同一客户只能加入任务一次，重复选择时保持幂等。
            }
        }
    }

    @Override
    public List<OutboundMemberResponse> listMembers(Long taskId) {
        requireTask(taskId);
        recoverExpired(taskId);
        return memberMapper.selectList(new LambdaQueryWrapper<OutboundMember>()
                .eq(OutboundMember::getTaskId, taskId)
                .orderByAsc(OutboundMember::getCreateTime))
            .stream().map(this::toMemberResponse).toList();
    }

    @Override
    public OutboundTaskStatisticsResponse statistics(Long taskId) {
        requireTask(taskId);
        recoverExpired(taskId);
        List<OutboundMember> members = memberMapper.selectList(new LambdaQueryWrapper<OutboundMember>()
            .eq(OutboundMember::getTaskId, taskId));
        OutboundTaskStatisticsResponse response = new OutboundTaskStatisticsResponse();
        response.setTaskId(taskId);
        response.setTotalCount(members.size());
        response.setPendingCount(countByStatus(members, "PENDING"));
        response.setClaimedCount(countByStatus(members, "CLAIMED"));
        response.setDialingCount(countByStatus(members, "DIALING"));
        response.setCompletedCount(countByStatus(members, "COMPLETED") + countByStatus(members, "SKIPPED"));
        response.setRetryCount(countByStatus(members, "RETRY"));
        response.setDialedCount(members.stream()
            .filter(member -> member.getAttemptCount() != null && member.getAttemptCount() > 0).count());
        response.setConnectedCount(members.stream()
            .filter(member -> "CONNECTED".equals(member.getResultCode())).count());
        Map<String, Long> distribution = members.stream()
            .filter(member -> member.getResultCode() != null && !member.getResultCode().isBlank())
            .collect(Collectors.groupingBy(OutboundMember::getResultCode, java.util.LinkedHashMap::new, Collectors.counting()));
        response.setResultDistribution(distribution);
        response.setCompletionRate(rate(response.getCompletedCount(), response.getTotalCount()));
        response.setConnectionRate(rate(response.getConnectedCount(), response.getDialedCount()));
        return response;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int recoverExpired(Long taskId) {
        requireTask(taskId);
        LocalDateTime now = LocalDateTime.now();
        int claimed = memberMapper.update(null, new LambdaUpdateWrapper<OutboundMember>()
            .eq(OutboundMember::getTaskId, taskId)
            .eq(OutboundMember::getStatus, "CLAIMED")
            .and(expired -> expired.le(OutboundMember::getLeaseExpiresAt, now)
                .or(legacy -> legacy.isNull(OutboundMember::getLeaseExpiresAt)
                    .le(OutboundMember::getClaimedAt, now.minusMinutes(CLAIM_LEASE_MINUTES))))
            .set(OutboundMember::getStatus, "PENDING")
            .set(OutboundMember::getClaimedAgentId, null)
            .set(OutboundMember::getClaimedUserId, null)
            .set(OutboundMember::getClaimedAt, null)
            .set(OutboundMember::getLeaseExpiresAt, null));
        int dialing = memberMapper.update(null, new LambdaUpdateWrapper<OutboundMember>()
            .eq(OutboundMember::getTaskId, taskId)
            .eq(OutboundMember::getStatus, "DIALING")
            .and(expired -> expired.le(OutboundMember::getLeaseExpiresAt, now)
                .or(legacy -> legacy.isNull(OutboundMember::getLeaseExpiresAt)
                    .le(OutboundMember::getClaimedAt, now.minusHours(DIALING_LEASE_HOURS))))
            .set(OutboundMember::getStatus, "RETRY")
            .set(OutboundMember::getResultCode, "OTHER")
            .set(OutboundMember::getResultRemark, "系统检测到外呼执行超时，已自动恢复为待重呼")
            .set(OutboundMember::getNextFollowUpAt, now)
            .set(OutboundMember::getClaimedAgentId, null)
            .set(OutboundMember::getClaimedUserId, null)
            .set(OutboundMember::getClaimedAt, null)
            .set(OutboundMember::getLeaseExpiresAt, null));
        if (claimed + dialing > 0) {
            log.info("外呼任务异常名单恢复完成，taskId={}，释放已领取名单={}，恢复拨打中名单={}", taskId, claimed, dialing);
        }
        return claimed + dialing;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutboundMemberResponse claimNext(Long taskId) {
        OutboundTask task = requireTask(taskId);
        recoverExpired(taskId);
        if (!"RUNNING".equals(task.getStatus())) throw new ServiceException("外呼任务未开始或已暂停");
        CurrentAgentResponse agent = requireAvailableAgent();
        Long userId = LoginHelper.getUserId();
        OutboundMember existing = memberMapper.selectOne(new LambdaQueryWrapper<OutboundMember>()
            .eq(OutboundMember::getTaskId, taskId)
            .eq(OutboundMember::getClaimedUserId, userId)
            .in(OutboundMember::getStatus, "CLAIMED", "DIALING")
            .last("LIMIT 1"));
        if (existing != null) return toMemberResponse(existing);

        OutboundMember candidate = memberMapper.selectOne(new LambdaQueryWrapper<OutboundMember>()
            .eq(OutboundMember::getTaskId, taskId)
            .and(condition -> condition.eq(OutboundMember::getStatus, "PENDING")
                .or(retry -> retry.eq(OutboundMember::getStatus, "RETRY")
                    .and(time -> time.isNull(OutboundMember::getNextFollowUpAt)
                        .or().le(OutboundMember::getNextFollowUpAt, LocalDateTime.now()))))
            .orderByAsc(OutboundMember::getNextFollowUpAt)
            .orderByAsc(OutboundMember::getCreateTime)
            .last("LIMIT 1"));
        if (candidate == null) throw new ServiceException("当前任务没有可领取的外呼名单");
        LocalDateTime now = LocalDateTime.now();
        int updated = memberMapper.update(null, new LambdaUpdateWrapper<OutboundMember>()
            .eq(OutboundMember::getId, candidate.getId())
            .in(OutboundMember::getStatus, "PENDING", "RETRY")
            .set(OutboundMember::getStatus, "CLAIMED")
            .set(OutboundMember::getClaimedAgentId, agent.getAgentId())
            .set(OutboundMember::getClaimedUserId, userId)
            .set(OutboundMember::getClaimedAt, now)
            .set(OutboundMember::getLeaseExpiresAt, now.plusMinutes(CLAIM_LEASE_MINUTES)));
        if (updated == 0) throw new ServiceException("该名单已被其他坐席领取，请重新领取");
        return toMemberResponse(requireMember(candidate.getId()));
    }

    @Override
    public OutboundMemberResponse renewLease(Long memberId) {
        OutboundMember member = requireOwnedMember(memberId);
        if (!Set.of("CLAIMED", "DIALING").contains(member.getStatus())) {
            throw new ServiceException("当前名单状态不需要续期");
        }
        LocalDateTime expiresAt = "DIALING".equals(member.getStatus())
            ? LocalDateTime.now().plusHours(DIALING_LEASE_HOURS)
            : LocalDateTime.now().plusMinutes(CLAIM_LEASE_MINUTES);
        int updated = memberMapper.update(null, new LambdaUpdateWrapper<OutboundMember>()
            .eq(OutboundMember::getId, memberId)
            .eq(OutboundMember::getClaimedUserId, LoginHelper.getUserId())
            .eq(OutboundMember::getStatus, member.getStatus())
            .set(OutboundMember::getLeaseExpiresAt, expiresAt));
        if (updated == 0) {
            throw new ServiceException("外呼名单状态已发生变化，请重新领取");
        }
        return toMemberResponse(requireMember(memberId));
    }

    @Override
    public OutboundMemberResponse dial(Long memberId) {
        OutboundMember member = requireOwnedMember(memberId);
        if (!EXECUTABLE_MEMBER_STATUSES.contains(member.getStatus())) {
            throw new ServiceException("当前名单状态不能拨打");
        }
        CallControlResponse call = callControlService.originate(member.getPhoneNumber(),
            new CallOriginateContext(member.getCustomerId(), member.getTaskId(), member.getId()));
        memberMapper.update(null, new LambdaUpdateWrapper<OutboundMember>()
            .eq(OutboundMember::getId, memberId)
            .eq(OutboundMember::getClaimedUserId, LoginHelper.getUserId())
            .set(OutboundMember::getStatus, "DIALING")
            .set(OutboundMember::getBusinessCallId, call.getCallId())
            .set(OutboundMember::getAttemptCount, member.getAttemptCount() + 1)
            .set(OutboundMember::getLeaseExpiresAt, LocalDateTime.now().plusHours(DIALING_LEASE_HOURS)));
        callBusinessAssociationService.associateCustomer(call.getCallId(), member.getCustomerId());
        return toMemberResponse(requireMember(memberId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void complete(Long memberId, CompleteOutboundMemberRequest request) {
        OutboundMember member = requireOwnedMember(memberId);
        boolean retry = Boolean.TRUE.equals(request.getRetry());
        validateCompletionRequest(request, retry);
        String nextStatus = retry ? "RETRY" : "COMPLETED";
        int updated = memberMapper.update(null, new LambdaUpdateWrapper<OutboundMember>()
            .eq(OutboundMember::getId, memberId)
            .eq(OutboundMember::getClaimedUserId, LoginHelper.getUserId())
            .set(OutboundMember::getStatus, nextStatus)
            .set(OutboundMember::getResultCode, request.getResultCode())
            .set(OutboundMember::getResultRemark, request.getResultRemark())
            .set(OutboundMember::getNextFollowUpAt, request.getNextFollowUpAt())
            .set(OutboundMember::getLeaseExpiresAt, null)
            .set(OutboundMember::getCompletedAt, retry ? null : LocalDateTime.now()));
        if (updated == 0) {
            throw new ServiceException("外呼名单状态已发生变化，请刷新后重试");
        }
        customerService.addFollowUp(member.getCustomerId(), buildFollowUpContent(request, retry));
        callBusinessAssociationService.associateCustomer(member.getBusinessCallId(), member.getCustomerId());
        completeTaskIfFinished(member.getTaskId());
    }

    private void validateCompletionRequest(CompleteOutboundMemberRequest request, boolean retry) {
        if (retry && request.getNextFollowUpAt() == null) {
            throw new ServiceException("需要重呼时必须设置下次跟进时间");
        }
        if (request.getNextFollowUpAt() != null && !request.getNextFollowUpAt().isAfter(LocalDateTime.now())) {
            throw new ServiceException("下次跟进时间必须晚于当前时间");
        }
        if ("FOLLOW_UP".equals(request.getResultCode()) && !retry) {
            throw new ServiceException("外呼结果为需要跟进时必须启用重呼");
        }
    }

    private String buildFollowUpContent(CompleteOutboundMemberRequest request, boolean retry) {
        StringBuilder content = new StringBuilder("预览式外呼结果：").append(resultLabel(request.getResultCode()));
        if (request.getResultRemark() != null && !request.getResultRemark().isBlank()) {
            content.append("\n结果备注：").append(request.getResultRemark().trim());
        }
        if (retry) {
            content.append("\n下次跟进：").append(request.getNextFollowUpAt().format(FOLLOW_UP_TIME_FORMATTER));
        }
        return content.toString();
    }

    private String resultLabel(String resultCode) {
        return switch (resultCode) {
            case "CONNECTED" -> "已接通";
            case "NO_ANSWER" -> "无人接听";
            case "BUSY" -> "客户忙";
            case "INVALID_NUMBER" -> "号码无效";
            case "NOT_INTERESTED" -> "无意向";
            case "FOLLOW_UP" -> "需要跟进";
            default -> "其他";
        };
    }

    private CurrentAgentResponse requireAvailableAgent() {
        CurrentAgentResponse agent = agentSessionService.current();
        if (!agent.isConfigured()) throw new ServiceException("当前用户尚未绑定坐席");
        if (agent.getStatus() == AgentPresenceStatus.OFFLINE) throw new ServiceException("坐席未签入，请先签入");
        return agent;
    }

    private OutboundTask requireTask(Long id) {
        OutboundTask task = taskMapper.selectById(id);
        if (task == null) throw new ServiceException("外呼任务不存在");
        return task;
    }

    private OutboundMember requireMember(Long id) {
        OutboundMember member = memberMapper.selectById(id);
        if (member == null) throw new ServiceException("外呼名单不存在");
        return member;
    }

    private OutboundMember requireOwnedMember(Long id) {
        OutboundMember member = requireMember(id);
        if (!LoginHelper.getUserId().equals(member.getClaimedUserId())) {
            throw new ServiceException("该外呼名单未由当前坐席领取");
        }
        return member;
    }

    private void applyTask(OutboundTask task, OutboundTaskRequest request) {
        task.setTaskCode(request.getTaskCode().trim());
        task.setTaskName(request.getTaskName().trim());
        task.setDescription(request.getDescription());
    }

    private void updateTaskStatus(Long id, String status) {
        taskMapper.update(null, new LambdaUpdateWrapper<OutboundTask>()
            .eq(OutboundTask::getId, id)
            .set(OutboundTask::getStatus, status));
    }

    private void completeTaskIfFinished(Long taskId) {
        long remaining = memberMapper.selectCount(new LambdaQueryWrapper<OutboundMember>()
            .eq(OutboundMember::getTaskId, taskId)
            .in(OutboundMember::getStatus, "PENDING", "RETRY", "CLAIMED", "DIALING"));
        if (remaining == 0) updateTaskStatus(taskId, "COMPLETED");
    }

    private OutboundTaskResponse toTaskResponse(OutboundTask task) {
        OutboundTaskResponse response = new OutboundTaskResponse();
        response.setId(task.getId());
        response.setTaskCode(task.getTaskCode());
        response.setTaskName(task.getTaskName());
        response.setTaskType(task.getTaskType());
        response.setStatus(task.getStatus());
        response.setDescription(task.getDescription());
        response.setTotalCount(countMembers(task.getId(), null));
        response.setPendingCount(countMembers(task.getId(), List.of("PENDING", "RETRY", "CLAIMED", "DIALING")));
        response.setCompletedCount(countMembers(task.getId(), List.of("COMPLETED", "SKIPPED")));
        response.setVersion(task.getVersion());
        response.setCreateTime(task.getCreateTime());
        return response;
    }

    private long countMembers(Long taskId, List<String> statuses) {
        LambdaQueryWrapper<OutboundMember> query = new LambdaQueryWrapper<OutboundMember>().eq(OutboundMember::getTaskId, taskId);
        if (statuses != null) query.in(OutboundMember::getStatus, statuses);
        return memberMapper.selectCount(query);
    }

    private long countByStatus(List<OutboundMember> members, String status) {
        return members.stream().filter(member -> status.equals(member.getStatus())).count();
    }

    private double rate(long numerator, long denominator) {
        return denominator == 0 ? 0D : Math.round(numerator * 10000D / denominator) / 100D;
    }

    private OutboundMemberResponse toMemberResponse(OutboundMember member) {
        OutboundMemberResponse response = new OutboundMemberResponse();
        response.setId(member.getId());
        response.setTaskId(member.getTaskId());
        response.setCustomerId(member.getCustomerId());
        response.setCustomerName(member.getCustomerName());
        response.setPhoneNumber(member.getPhoneNumber());
        response.setStatus(member.getStatus());
        response.setClaimedAgentId(member.getClaimedAgentId());
        response.setClaimedAt(member.getClaimedAt());
        response.setLeaseExpiresAt(member.getLeaseExpiresAt());
        response.setBusinessCallId(member.getBusinessCallId());
        response.setAttemptCount(member.getAttemptCount());
        response.setResultCode(member.getResultCode());
        response.setResultRemark(member.getResultRemark());
        response.setNextFollowUpAt(member.getNextFollowUpAt());
        response.setCompletedAt(member.getCompletedAt());
        return response;
    }
}
