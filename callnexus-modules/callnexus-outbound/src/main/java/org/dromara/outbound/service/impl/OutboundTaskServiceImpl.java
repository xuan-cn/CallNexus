package org.dromara.outbound.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.agent.domain.AgentPresenceStatus;
import org.dromara.agent.domain.response.CurrentAgentResponse;
import org.dromara.agent.service.CurrentAgentSessionService;
import org.dromara.agent.service.AgentAvailabilityQueryService;
import org.dromara.agent.service.model.AgentAvailability;
import org.dromara.call.domain.response.CallControlResponse;
import org.dromara.call.domain.CallOriginateContext;
import org.dromara.call.service.CallBusinessAssociationService;
import org.dromara.call.service.CallControlApplicationService;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.customer.customer.domain.response.CustomerResponse;
import org.dromara.customer.customer.service.CustomerApplicationService;
import org.dromara.outbound.domain.OutboundMember;
import org.dromara.outbound.domain.OutboundAttempt;
import org.dromara.outbound.domain.OutboundTask;
import org.dromara.outbound.domain.request.CompleteOutboundMemberRequest;
import org.dromara.outbound.domain.request.OutboundAttemptPageQuery;
import org.dromara.outbound.domain.request.OutboundTaskRequest;
import org.dromara.outbound.domain.response.OutboundMemberResponse;
import org.dromara.outbound.domain.response.OutboundAttemptResponse;
import org.dromara.outbound.domain.response.OutboundTaskStatisticsResponse;
import org.dromara.outbound.domain.response.OutboundTaskResponse;
import org.dromara.outbound.domain.response.AddOutboundMembersResponse;
import org.dromara.outbound.domain.response.OutboundBlacklistMatch;
import org.dromara.outbound.mapper.OutboundMemberMapper;
import org.dromara.outbound.mapper.OutboundAttemptMapper;
import org.dromara.outbound.mapper.OutboundImportBatchMapper;
import org.dromara.outbound.mapper.OutboundImportRowMapper;
import org.dromara.outbound.mapper.OutboundTaskMapper;
import org.dromara.outbound.domain.OutboundImportBatch;
import org.dromara.outbound.domain.OutboundImportRow;
import org.dromara.outbound.service.OutboundAutomaticRetryService;
import org.dromara.outbound.service.OutboundResultSuggestionService;
import org.dromara.outbound.service.OutboundTaskService;
import org.dromara.outbound.service.OutboundBlacklistChecker;
import org.dromara.outbound.service.OutboundBlacklistMemberSyncService;
import org.dromara.outbound.service.PhoneNumberNormalizer;
import org.dromara.system.callcenterconfig.service.CallCenterConfigService;
import org.springframework.dao.DuplicateKeyException;
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
@Slf4j
public class OutboundTaskServiceImpl implements OutboundTaskService {
    private static final Set<String> EXECUTABLE_MEMBER_STATUSES = Set.of("CLAIMED", "RETRY");
    private static final DateTimeFormatter FOLLOW_UP_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int DEFAULT_CLAIM_LEASE_MINUTES = 15;
    private static final int DEFAULT_DIALING_LEASE_MINUTES = 120;
    private static final int DEFAULT_MAX_RETRY_COUNT = 2;
    private static final int DEFAULT_RETRY_INTERVAL_MINUTES = 30;
    private static final String DEFAULT_RETRY_RESULT_CODES = "NO_ANSWER,BUSY,OTHER";

    private final OutboundTaskMapper taskMapper;
    private final OutboundMemberMapper memberMapper;
    private final OutboundAttemptMapper attemptMapper;
    private final OutboundImportBatchMapper importBatchMapper;
    private final OutboundImportRowMapper importRowMapper;
    private final CustomerApplicationService customerService;
    private final CurrentAgentSessionService agentSessionService;
    private final AgentAvailabilityQueryService agentAvailabilityQueryService;
    private final CallControlApplicationService callControlService;
    private final CallBusinessAssociationService callBusinessAssociationService;
    private final OutboundResultSuggestionService resultSuggestionService;
    private final OutboundAutomaticRetryService automaticRetryService;
    private final OutboundBlacklistChecker blacklistChecker;
    private final OutboundBlacklistMemberSyncService blacklistMemberSyncService;
    private final PhoneNumberNormalizer phoneNumberNormalizer;
    private final CallCenterConfigService callCenterConfigService;

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
        attemptMapper.delete(new LambdaQueryWrapper<OutboundAttempt>().eq(OutboundAttempt::getTaskId, id));
        memberMapper.delete(new LambdaQueryWrapper<OutboundMember>().eq(OutboundMember::getTaskId, id));
        List<Long> batchIds = importBatchMapper.selectList(new LambdaQueryWrapper<OutboundImportBatch>()
                .eq(OutboundImportBatch::getTaskId, id))
            .stream().map(OutboundImportBatch::getId).toList();
        if (!batchIds.isEmpty()) {
            importRowMapper.delete(new LambdaQueryWrapper<OutboundImportRow>().in(OutboundImportRow::getBatchId, batchIds));
            importBatchMapper.delete(new LambdaQueryWrapper<OutboundImportBatch>().in(OutboundImportBatch::getId, batchIds));
        }
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
    public AddOutboundMembersResponse addCustomers(Long id, List<Long> customerIds) {
        OutboundTask task = requireTask(id);
        if ("RUNNING".equals(task.getStatus())) throw new ServiceException("执行中的外呼任务不能添加名单，请先暂停");
        AddOutboundMembersResponse response = new AddOutboundMembersResponse();
        for (Long customerId : customerIds.stream().distinct().toList()) {
            CustomerResponse customer = customerService.get(customerId);
            String normalizedPhone = phoneNumberNormalizer.normalize(customer.getPrimaryPhone());
            OutboundBlacklistMatch match = blacklistChecker.check(id, normalizedPhone);
            if (match != null) {
                AddOutboundMembersResponse.BlockedMemberDetail detail = new AddOutboundMembersResponse.BlockedMemberDetail();
                detail.setCustomerId(customerId);
                detail.setCustomerName(customer.getCustomerName());
                detail.setPhoneNumber(normalizedPhone);
                detail.setReason(match.getReason());
                detail.setBlacklistId(match.getBlacklistId());
                response.getBlocked().add(detail);
                continue;
            }
            OutboundMember member = new OutboundMember();
            member.setTaskId(id);
            member.setCustomerId(customerId);
            member.setCustomerName(customer.getCustomerName());
            member.setPhoneNumber(normalizedPhone);
            member.setSourceType("MANUAL");
            member.setStatus("PENDING");
            member.setAttemptCount(0);
            try {
                memberMapper.insert(member);
                response.setAddedCount(response.getAddedCount() + 1);
            } catch (DuplicateKeyException ignored) {
                response.setDuplicateCount(response.getDuplicateCount() + 1);
            }
        }
        return response;
    }

    @Override
    public List<OutboundMemberResponse> listMembers(Long taskId) {
        requireTask(taskId);
        blacklistMemberSyncService.restoreExpired();
        recoverExpired(taskId);
        return memberMapper.selectList(new LambdaQueryWrapper<OutboundMember>()
                .eq(OutboundMember::getTaskId, taskId)
                .orderByAsc(OutboundMember::getCreateTime))
            .stream().map(this::toMemberResponse).toList();
    }

    @Override
    public List<OutboundAttemptResponse> listAttempts(Long memberId) {
        requireMember(memberId);
        return attemptMapper.selectList(new LambdaQueryWrapper<OutboundAttempt>()
                .eq(OutboundAttempt::getMemberId, memberId)
                .orderByDesc(OutboundAttempt::getAttemptNo))
            .stream().map(this::toAttemptResponse).toList();
    }

    @Override
    public TableDataInfo<OutboundAttemptResponse> pageAttempts(OutboundAttemptPageQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<OutboundAttempt> wrapper = new LambdaQueryWrapper<OutboundAttempt>()
            .eq(query.getTaskId() != null, OutboundAttempt::getTaskId, query.getTaskId())
            .eq(query.getAgentId() != null, OutboundAttempt::getAgentId, query.getAgentId())
            .like(StringUtils.isNotBlank(query.getPhoneNumber()), OutboundAttempt::getPhoneNumber, query.getPhoneNumber())
            .eq(StringUtils.isNotBlank(query.getResultCode()), OutboundAttempt::getResultCode, query.getResultCode())
            .eq(StringUtils.isNotBlank(query.getSuggestedResultCode()), OutboundAttempt::getSuggestedResultCode, query.getSuggestedResultCode())
            .eq(StringUtils.isNotBlank(query.getHangupCause()), OutboundAttempt::getHangupCause, query.getHangupCause())
            .ge(query.getStartedAtBegin() != null, OutboundAttempt::getStartedAt, query.getStartedAtBegin())
            .le(query.getStartedAtEnd() != null, OutboundAttempt::getStartedAt, query.getStartedAtEnd())
            .orderByDesc(OutboundAttempt::getStartedAt);
        Page<OutboundAttempt> page = attemptMapper.selectPage(pageQuery.build(), wrapper);
        return new TableDataInfo<>(page.getRecords().stream().map(this::toAttemptResponse).toList(), page.getTotal());
    }

    @Override
    public OutboundTaskStatisticsResponse statistics(Long taskId) {
        requireTask(taskId);
        blacklistMemberSyncService.restoreExpired();
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
        response.setWaitingRetryCount(members.stream()
            .filter(member -> "RETRY".equals(member.getStatus()))
            .filter(member -> member.getNextFollowUpAt() != null && member.getNextFollowUpAt().isAfter(LocalDateTime.now()))
            .count());
        response.setRetryLimitReachedCount(members.stream()
            .filter(member -> "RETRY_LIMIT_REACHED".equals(member.getCompletionReason()))
            .count());
        response.setBlockedCount(countByStatus(members, "BLOCKED"));
        List<OutboundAttempt> attempts = attemptMapper.selectList(new LambdaQueryWrapper<OutboundAttempt>()
            .eq(OutboundAttempt::getTaskId, taskId));
        response.setDialedCount(attempts.stream().map(OutboundAttempt::getMemberId).distinct().count());
        response.setConnectedCount(members.stream()
            .filter(member -> "CONNECTED".equals(member.getResultCode())).count());
        response.setTotalAttemptCount(attempts.size());
        response.setAnsweredAttemptCount(attempts.stream()
            .filter(attempt -> attempt.getAnsweredAt() != null).count());
        Map<String, Long> distribution = members.stream()
            .filter(member -> member.getResultCode() != null && !member.getResultCode().isBlank())
            .collect(Collectors.groupingBy(OutboundMember::getResultCode, java.util.LinkedHashMap::new, Collectors.counting()));
        response.setResultDistribution(distribution);
        response.setCompletionRate(rate(response.getCompletedCount(), response.getTotalCount()));
        response.setConnectionRate(rate(response.getConnectedCount(), response.getDialedCount()));
        response.setAttemptConnectionRate(rate(response.getAnsweredAttemptCount(), response.getTotalAttemptCount()));
        return response;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int recoverExpired(Long taskId) {
        requireTask(taskId);
        LocalDateTime now = LocalDateTime.now();
        int claimLeaseMinutes = claimLeaseMinutes();
        int dialingLeaseMinutes = dialingLeaseMinutes();
        int claimed = memberMapper.update(null, new LambdaUpdateWrapper<OutboundMember>()
            .eq(OutboundMember::getTaskId, taskId)
            .eq(OutboundMember::getStatus, "CLAIMED")
            .and(expired -> expired.le(OutboundMember::getLeaseExpiresAt, now)
                .or(legacy -> legacy.isNull(OutboundMember::getLeaseExpiresAt)
                    .le(OutboundMember::getClaimedAt, now.minusMinutes(claimLeaseMinutes))))
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
                    .le(OutboundMember::getClaimedAt, now.minusMinutes(dialingLeaseMinutes))))
            .set(OutboundMember::getStatus, "RETRY")
            .set(OutboundMember::getResultCode, "OTHER")
            .set(OutboundMember::getResultRemark, "系统检测到外呼执行超时，已自动恢复为待重呼")
            .set(OutboundMember::getNextFollowUpAt, now)
            .set(OutboundMember::getClaimedAgentId, null)
            .set(OutboundMember::getClaimedUserId, null)
            .set(OutboundMember::getClaimedAt, null)
            .set(OutboundMember::getLeaseExpiresAt, null));
        if (dialing > 0) {
            attemptMapper.update(null, new LambdaUpdateWrapper<OutboundAttempt>()
                .eq(OutboundAttempt::getTaskId, taskId)
                .eq(OutboundAttempt::getStatus, "DIALING")
                .le(OutboundAttempt::getStartedAt, now.minusMinutes(dialingLeaseMinutes))
                .set(OutboundAttempt::getStatus, "ENDED")
                .set(OutboundAttempt::getEndedAt, now)
                .set(OutboundAttempt::getSuggestedResultCode, "OTHER")
                .set(OutboundAttempt::getHangupCause, "SYSTEM_RECOVERED"));
        }
        if (claimed + dialing > 0) {
            log.info("外呼任务异常名单恢复完成，taskId={}，释放已领取名单={}，恢复拨打中名单={}", taskId, claimed, dialing);
        }
        return claimed + dialing;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutboundMemberResponse claimNext(Long taskId) {
        OutboundTask task = requireTask(taskId);
        blacklistMemberSyncService.restoreExpired();
        recoverExpired(taskId);
        if (!"RUNNING".equals(task.getStatus())) {
            long dueRetryCount = memberMapper.selectCount(new LambdaQueryWrapper<OutboundMember>()
                .eq(OutboundMember::getTaskId, taskId)
                .eq(OutboundMember::getStatus, "RETRY")
                .and(time -> time.isNull(OutboundMember::getNextFollowUpAt)
                    .or().le(OutboundMember::getNextFollowUpAt, LocalDateTime.now())));
            if ("COMPLETED".equals(task.getStatus()) && dueRetryCount > 0) {
                updateTaskStatus(taskId, "RUNNING");
            } else {
                throw new ServiceException("外呼任务未开始、已暂停或尚未到达重呼时间");
            }
        }
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
        OutboundBlacklistMatch candidateMatch = blacklistChecker.check(taskId, candidate.getPhoneNumber());
        if (candidateMatch != null) {
            blacklistMemberSyncService.blockMember(candidate, candidateMatch);
            completeTaskIfFinished(taskId);
            return claimNext(taskId);
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = memberMapper.update(null, new LambdaUpdateWrapper<OutboundMember>()
            .eq(OutboundMember::getId, candidate.getId())
            .in(OutboundMember::getStatus, "PENDING", "RETRY")
            .set(OutboundMember::getStatus, "CLAIMED")
            .set(OutboundMember::getClaimedAgentId, agent.getAgentId())
            .set(OutboundMember::getClaimedUserId, userId)
            .set(OutboundMember::getClaimedAt, now)
            .set(OutboundMember::getLeaseExpiresAt, now.plusMinutes(claimLeaseMinutes())));
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
            ? LocalDateTime.now().plusMinutes(dialingLeaseMinutes())
            : LocalDateTime.now().plusMinutes(claimLeaseMinutes());
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
    public OutboundMemberResponse currentAssigned() {
        CurrentAgentResponse agent = agentSessionService.current();
        if (!agent.isConfigured() || agent.getAgentId() == null) {
            return null;
        }
        OutboundMember member = memberMapper.selectOne(new LambdaQueryWrapper<OutboundMember>()
            .eq(OutboundMember::getClaimedUserId, LoginHelper.getUserId())
            .eq(OutboundMember::getClaimedAgentId, agent.getAgentId())
            .in(OutboundMember::getStatus, "CLAIMED", "DIALING")
            .gt(OutboundMember::getLeaseExpiresAt, LocalDateTime.now())
            .orderByDesc(OutboundMember::getClaimedAt)
            .last("LIMIT 1"));
        return member == null ? null : toMemberResponse(member);
    }

    @Override
    public OutboundMemberResponse dial(Long memberId) {
        OutboundMember member = requireOwnedMember(memberId);
        if (!EXECUTABLE_MEMBER_STATUSES.contains(member.getStatus())) {
            throw new ServiceException("当前名单状态不能拨打");
        }
        OutboundBlacklistMatch dialMatch = blacklistChecker.check(member.getTaskId(), member.getPhoneNumber());
        if (dialMatch != null) {
            blacklistMemberSyncService.blockMember(member, dialMatch);
            completeTaskIfFinished(member.getTaskId());
            throw new ServiceException("该电话号码已命中外呼黑名单，已停止拨打");
        }
        int attemptNo = Math.toIntExact(attemptMapper.selectCount(new LambdaQueryWrapper<OutboundAttempt>()
            .eq(OutboundAttempt::getMemberId, memberId)) + 1);
        String businessCallId = UUID.randomUUID().toString();
        OutboundAttempt attempt = new OutboundAttempt();
        attempt.setTaskId(member.getTaskId());
        attempt.setMemberId(member.getId());
        attempt.setCustomerId(member.getCustomerId());
        OutboundTask task = requireTask(member.getTaskId());
        attempt.setTaskName(task.getTaskName());
        attempt.setCustomerName(member.getCustomerName());
        attempt.setPhoneNumber(member.getPhoneNumber());
        attempt.setAgentId(member.getClaimedAgentId());
        attempt.setUserId(member.getClaimedUserId());
        attempt.setAttemptNo(attemptNo);
        attempt.setBusinessCallId(businessCallId);
        attempt.setStatus("DIALING");
        attempt.setStartedAt(LocalDateTime.now());
        attempt.setDurationSeconds(0);
        attempt.setBillableSeconds(0);
        attemptMapper.insert(attempt);
        CallControlResponse call;
        try {
            call = callControlService.originate(member.getPhoneNumber(),
                new CallOriginateContext(businessCallId, member.getCustomerId(), member.getTaskId(), member.getId(), task.getCallerNumberId()));
        } catch (RuntimeException exception) {
            attemptMapper.update(null, new LambdaUpdateWrapper<OutboundAttempt>()
                .eq(OutboundAttempt::getId, attempt.getId())
                .set(OutboundAttempt::getStatus, "ENDED")
                .set(OutboundAttempt::getEndedAt, LocalDateTime.now())
                .set(OutboundAttempt::getSuggestedResultCode, "OTHER")
                .set(OutboundAttempt::getHangupCause, "ORIGINATE_FAILED"));
            memberMapper.update(null, new LambdaUpdateWrapper<OutboundMember>()
                .eq(OutboundMember::getId, memberId)
                .set(OutboundMember::getStatus, "DIALING")
                .set(OutboundMember::getBusinessCallId, businessCallId)
                .set(OutboundMember::getAttemptCount, attemptNo));
            automaticRetryService.applySystemSuggestion(memberId, businessCallId, "OTHER");
            throw exception;
        }
        memberMapper.update(null, new LambdaUpdateWrapper<OutboundMember>()
            .eq(OutboundMember::getId, memberId)
            .eq(OutboundMember::getClaimedUserId, LoginHelper.getUserId())
            .set(OutboundMember::getStatus, "DIALING")
            .set(OutboundMember::getBusinessCallId, call.getBusinessCallId())
            .set(OutboundMember::getAttemptCount, attemptNo)
            .set(OutboundMember::getLeaseExpiresAt, LocalDateTime.now().plusMinutes(dialingLeaseMinutes())));
        callBusinessAssociationService.associateCustomer(call.getBusinessCallId(), member.getCustomerId());
        return toMemberResponse(requireMember(memberId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void complete(Long memberId, CompleteOutboundMemberRequest request) {
        OutboundMember member = requireOwnedMember(memberId);
        OutboundTask task = requireTask(member.getTaskId());
        boolean manualRetry = Boolean.TRUE.equals(request.getRetry());
        validateCompletionRequest(request, manualRetry);
        boolean automaticRetryResult = Boolean.TRUE.equals(task.getAutoRetryEnabled())
            && retryResultCodes(task).contains(request.getResultCode());
        int maxRetryCount = task.getMaxRetryCount() == null ? DEFAULT_MAX_RETRY_COUNT : task.getMaxRetryCount();
        boolean retryLimitReached = automaticRetryResult && member.getAttemptCount() != null
            && member.getAttemptCount() > maxRetryCount;
        boolean retry = manualRetry || automaticRetryResult && !retryLimitReached;
        LocalDateTime nextRetryAt = manualRetry ? request.getNextFollowUpAt()
            : automaticRetryResult && member.getNextFollowUpAt() != null ? member.getNextFollowUpAt()
            : retry ? LocalDateTime.now().plusMinutes(task.getRetryIntervalMinutes() == null
                ? DEFAULT_RETRY_INTERVAL_MINUTES : task.getRetryIntervalMinutes()) : null;
        String nextStatus = retry ? "RETRY" : "COMPLETED";
        int updated = memberMapper.update(null, new LambdaUpdateWrapper<OutboundMember>()
            .eq(OutboundMember::getId, memberId)
            .eq(OutboundMember::getClaimedUserId, LoginHelper.getUserId())
            .set(OutboundMember::getStatus, nextStatus)
            .set(OutboundMember::getResultCode, request.getResultCode())
            .set(OutboundMember::getResultRemark, request.getResultRemark())
            .set(OutboundMember::getNextFollowUpAt, nextRetryAt)
            .set(OutboundMember::getLeaseExpiresAt, null)
            .set(OutboundMember::getCompletedAt, retry ? null : LocalDateTime.now())
            .set(OutboundMember::getCompletionReason, retry ? null : retryLimitReached ? "RETRY_LIMIT_REACHED" : "MANUAL"));
        if (updated == 0) {
            throw new ServiceException("外呼名单状态已发生变化，请刷新后重试");
        }
        attemptMapper.update(null, new LambdaUpdateWrapper<OutboundAttempt>()
            .eq(OutboundAttempt::getBusinessCallId, member.getBusinessCallId())
            .set(OutboundAttempt::getResultCode, request.getResultCode())
            .set(OutboundAttempt::getResultRemark, request.getResultRemark()));
        customerService.addFollowUp(member.getCustomerId(), buildFollowUpContent(request, retry, nextRetryAt));
        callBusinessAssociationService.associateCustomer(member.getBusinessCallId(), member.getCustomerId());
        if (retry) updateTaskStatus(member.getTaskId(), "RUNNING");
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

    private String buildFollowUpContent(CompleteOutboundMemberRequest request, boolean retry, LocalDateTime nextRetryAt) {
        StringBuilder content = new StringBuilder("预览式外呼结果：").append(resultLabel(request.getResultCode()));
        if (request.getResultRemark() != null && !request.getResultRemark().isBlank()) {
            content.append("\n结果备注：").append(request.getResultRemark().trim());
        }
        if (retry) {
            content.append("\n下次重呼：").append(nextRetryAt.format(FOLLOW_UP_TIME_FORMATTER));
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
        task.setCallerNumberId(request.getCallerNumberId());
        task.setAutoRetryEnabled(request.getAutoRetryEnabled() == null || request.getAutoRetryEnabled());
        task.setMaxRetryCount(request.getMaxRetryCount() == null ? DEFAULT_MAX_RETRY_COUNT : request.getMaxRetryCount());
        task.setRetryIntervalMinutes(request.getRetryIntervalMinutes() == null
            ? DEFAULT_RETRY_INTERVAL_MINUTES : request.getRetryIntervalMinutes());
        task.setRetryResultCodes(StringUtils.isBlank(request.getRetryResultCodes())
            ? DEFAULT_RETRY_RESULT_CODES : request.getRetryResultCodes());
        task.setAutoAssignDueRetry(Boolean.TRUE.equals(request.getAutoAssignDueRetry()));
        task.setRetryAssigneeAgentId(Boolean.TRUE.equals(task.getAutoAssignDueRetry())
            ? request.getRetryAssigneeAgentId() : null);
        if (Boolean.TRUE.equals(task.getAutoAssignDueRetry()) && task.getRetryAssigneeAgentId() == null) {
            throw new ServiceException("启用到期重呼自动分配时必须选择指定坐席");
        }
        if (Boolean.TRUE.equals(task.getAutoAssignDueRetry())) {
            AgentAvailability agent = agentAvailabilityQueryService.get(task.getRetryAssigneeAgentId());
            if (agent == null || !agent.enabled() || agent.userId() == null) {
                throw new ServiceException("指定坐席不存在、已停用或未绑定系统用户");
            }
        }
    }

    private Set<String> retryResultCodes(OutboundTask task) {
        String configured = StringUtils.isBlank(task.getRetryResultCodes())
            ? DEFAULT_RETRY_RESULT_CODES : task.getRetryResultCodes();
        return java.util.Arrays.stream(configured.split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .collect(Collectors.toSet());
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
        response.setCallerNumberId(task.getCallerNumberId());
        response.setAutoRetryEnabled(task.getAutoRetryEnabled());
        response.setMaxRetryCount(task.getMaxRetryCount());
        response.setRetryIntervalMinutes(task.getRetryIntervalMinutes());
        response.setRetryResultCodes(task.getRetryResultCodes());
        response.setAutoAssignDueRetry(task.getAutoAssignDueRetry());
        response.setRetryAssigneeAgentId(task.getRetryAssigneeAgentId());
        response.setTotalCount(countMembers(task.getId(), null));
        response.setPendingCount(countMembers(task.getId(), List.of("PENDING", "RETRY", "CLAIMED", "DIALING")));
        response.setCompletedCount(countMembers(task.getId(), List.of("COMPLETED", "SKIPPED")));
        response.setDueRetryCount(memberMapper.selectCount(new LambdaQueryWrapper<OutboundMember>()
            .eq(OutboundMember::getTaskId, task.getId())
            .eq(OutboundMember::getStatus, "RETRY")
            .and(time -> time.isNull(OutboundMember::getNextFollowUpAt)
                .or().le(OutboundMember::getNextFollowUpAt, LocalDateTime.now()))));
        response.setLastScheduledAt(task.getLastScheduledAt());
        response.setLastScheduleSummary(task.getLastScheduleSummary());
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

    private int claimLeaseMinutes() {
        return positiveConfig("outbound.claimLeaseMinutes", DEFAULT_CLAIM_LEASE_MINUTES);
    }

    private int dialingLeaseMinutes() {
        return positiveConfig("outbound.dialingLeaseMinutes", DEFAULT_DIALING_LEASE_MINUTES);
    }

    private int positiveConfig(String configKey, int defaultValue) {
        Integer value = callCenterConfigService.getInt(configKey);
        return value == null || value < 1 ? defaultValue : value;
    }

    private OutboundMemberResponse toMemberResponse(OutboundMember member) {
        OutboundMemberResponse response = new OutboundMemberResponse();
        response.setId(member.getId());
        response.setTaskId(member.getTaskId());
        response.setCustomerId(member.getCustomerId());
        response.setCustomerName(member.getCustomerName());
        response.setPhoneNumber(member.getPhoneNumber());
        response.setSourceType(member.getSourceType());
        response.setImportBatchId(member.getImportBatchId());
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
        response.setCompletionReason(member.getCompletionReason());
        response.setBlockedReason(member.getBlockedReason());
        response.setBlockedAt(member.getBlockedAt());
        response.setBlockedBlacklistId(member.getBlockedBlacklistId());
        return response;
    }

    private OutboundAttemptResponse toAttemptResponse(OutboundAttempt attempt) {
        OutboundAttemptResponse response = new OutboundAttemptResponse();
        response.setId(attempt.getId());
        response.setTaskId(attempt.getTaskId());
        response.setMemberId(attempt.getMemberId());
        response.setCustomerId(attempt.getCustomerId());
        response.setTaskName(attempt.getTaskName());
        response.setCustomerName(attempt.getCustomerName());
        response.setPhoneNumber(attempt.getPhoneNumber());
        response.setAgentId(attempt.getAgentId());
        response.setUserId(attempt.getUserId());
        response.setAttemptNo(attempt.getAttemptNo());
        response.setBusinessCallId(attempt.getBusinessCallId());
        response.setStatus(attempt.getStatus());
        response.setResultCode(attempt.getResultCode());
        response.setResultRemark(attempt.getResultRemark());
        response.setSuggestedResultCode(attempt.getSuggestedResultCode());
        response.setSuggestedResultLabel(resultSuggestionService.resultLabel(attempt.getSuggestedResultCode()));
        response.setStartedAt(attempt.getStartedAt());
        response.setAnsweredAt(attempt.getAnsweredAt());
        response.setEndedAt(attempt.getEndedAt());
        response.setDurationSeconds(attempt.getDurationSeconds());
        response.setBillableSeconds(attempt.getBillableSeconds());
        response.setHangupCause(attempt.getHangupCause());
        response.setHangupCauseLabel(resultSuggestionService.hangupCauseLabel(attempt.getHangupCause()));
        return response;
    }
}
