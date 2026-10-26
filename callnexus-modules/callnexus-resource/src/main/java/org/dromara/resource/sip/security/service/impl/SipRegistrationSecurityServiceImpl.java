package org.dromara.resource.sip.security.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.sip.domain.SipAccount;
import org.dromara.resource.sip.mapper.SipAccountMapper;
import org.dromara.resource.sip.security.domain.SipRegistrationBlock;
import org.dromara.resource.sip.security.domain.SipRegistrationSecurityEvent;
import org.dromara.resource.sip.security.domain.SipRegistrationSecurityPolicy;
import org.dromara.resource.sip.security.domain.request.SipRegistrationEventRequest;
import org.dromara.resource.sip.security.domain.response.SipRegistrationSecuritySummaryResponse;
import org.dromara.resource.sip.security.mapper.SipRegistrationBlockMapper;
import org.dromara.resource.sip.security.mapper.SipRegistrationSecurityEventMapper;
import org.dromara.resource.sip.security.service.SipRegistrationSecurityService;
import org.dromara.resource.sip.security.service.SipRegistrationSecurityManagementService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SipRegistrationSecurityServiceImpl implements SipRegistrationSecurityService {
    private final SipAccountMapper accountMapper;
    private final SipRegistrationSecurityEventMapper eventMapper;
    private final SipRegistrationBlockMapper blockMapper;
    private final SipRegistrationSecurityManagementService managementService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void record(SipRegistrationEventRequest request) {
        TenantHelper.dynamic(request.getTenantId(), () -> recordInTenant(request));
    }

    protected void recordInTenant(SipRegistrationEventRequest request) {
        if (eventMapper.exists(new LambdaQueryWrapper<SipRegistrationSecurityEvent>()
            .eq(SipRegistrationSecurityEvent::getEventKey, request.getEventKey()))) return;

        SipAccount account = resolveAccount(request.getNodeId(), request.getExtension(), request.getAuthUsername());
        SipRegistrationSecurityEvent event = new SipRegistrationSecurityEvent();
        event.setEventKey(request.getEventKey());
        event.setNodeId(request.getNodeId());
        event.setSipAccountId(account == null ? null : account.getId());
        event.setExtensionSnapshot(account == null ? request.getExtension() : account.getExtension());
        event.setAuthUsernameSnapshot(account == null ? request.getAuthUsername() : account.getAuthUsername());
        event.setSourceIp(normalize(request.getSourceIp()));
        event.setSourcePort(request.getSourcePort());
        event.setTransport(normalizeUpper(request.getTransport()));
        event.setUserAgent(normalize(request.getUserAgent()));
        event.setResult(request.getResult());
        event.setReasonCode(normalizeUpper(request.getReasonCode()));
        event.setOccurredAt(request.getOccurredAt());
        event.setRiskLevel("FAILURE".equals(request.getResult()) ? "MEDIUM" : "LOW");
        try {
            eventMapper.insert(event);
        } catch (DuplicateKeyException ignored) {
            return;
        }
        if (!"FAILURE".equals(request.getResult())) return;

        evaluateBlocks(event);
    }

    @Override
    public boolean isBlocked(String tenantId, Long nodeId, Long sipAccountId, String sourceIp) {
        if (tenantId == null || sipAccountId == null) return false;
        return TenantHelper.dynamic(tenantId, () -> {
            String normalizedIp = normalize(sourceIp);
            if (blockMapper.exists(activeAccountOrPairBlockQuery(nodeId, sipAccountId, normalizedIp))) return true;
            return normalizedIp != null && !managementService.isIpAllowlisted(nodeId, normalizedIp)
                && blockMapper.exists(activeIpBlockQuery(nodeId, normalizedIp));
        });
    }

    @Override
    public SipRegistrationSecuritySummaryResponse summary(Long sipAccountId) {
        SipAccount account = requireAccount(sipAccountId);
        LocalDateTime now = LocalDateTime.now();
        SipRegistrationSecuritySummaryResponse response = new SipRegistrationSecuritySummaryResponse();
        response.setSipAccountId(sipAccountId);

        SipRegistrationSecurityEvent lastFailure = eventMapper.selectOne(new LambdaQueryWrapper<SipRegistrationSecurityEvent>()
            .eq(SipRegistrationSecurityEvent::getSipAccountId, sipAccountId)
            .eq(SipRegistrationSecurityEvent::getResult, "FAILURE")
            .orderByDesc(SipRegistrationSecurityEvent::getOccurredAt)
            .last("LIMIT 1"));
        if (lastFailure != null) {
            response.setLastFailureAt(lastFailure.getOccurredAt());
            response.setLastFailureIp(lastFailure.getSourceIp());
        }
        response.setRecentFailureCount(Math.toIntExact(eventMapper.selectCount(new LambdaQueryWrapper<SipRegistrationSecurityEvent>()
            .eq(SipRegistrationSecurityEvent::getSipAccountId, sipAccountId)
            .eq(SipRegistrationSecurityEvent::getResult, "FAILURE")
            .ge(SipRegistrationSecurityEvent::getOccurredAt, now.minusMinutes(10)))));

        SipRegistrationBlock block = blockMapper.selectOne(new LambdaQueryWrapper<SipRegistrationBlock>()
            .eq(SipRegistrationBlock::getSipAccountId, sipAccountId)
            .eq(SipRegistrationBlock::getStatus, "ACTIVE")
            .and(wrapper -> wrapper.isNull(SipRegistrationBlock::getExpiresAt)
                .or().gt(SipRegistrationBlock::getExpiresAt, now))
            .orderByAsc(SipRegistrationBlock::getExpiresAt)
            .last("LIMIT 1"));
        if (block == null) {
            int watchingThreshold = Math.max(2, managementService.effectivePolicy(account.getNodeId()).getPairFailureThreshold()) - 1;
            response.setStatus(Boolean.TRUE.equals(account.getEnabled())
                ? response.getRecentFailureCount() >= watchingThreshold ? "WATCHING" : "NORMAL"
                : "DISABLED");
            return response;
        }
        response.setStatus(block.getExpiresAt() == null ? "MANUAL_LOCKED" : "TEMP_LOCKED");
        response.setLockedUntil(block.getExpiresAt());
        response.setLockReason(block.getReasonDetail());
        response.setActiveBlockId(block.getId());
        return response;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void manualLock(Long sipAccountId, String reason) {
        SipAccount account = requireAccount(sipAccountId);
        if (hasActiveBlock(account.getNodeId(), sipAccountId, null, "ACCOUNT")) {
            throw new ServiceException("该分机已经处于锁定状态");
        }
        createBlock(account.getNodeId(), sipAccountId, null, "ACCOUNT", null,
            "MANUAL_LOCK", defaultReason(reason, "管理员手工锁定"), 1);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unlock(Long sipAccountId, String reason) {
        requireAccount(sipAccountId);
        LocalDateTime now = LocalDateTime.now();
        int updated = blockMapper.update(null, new LambdaUpdateWrapper<SipRegistrationBlock>()
            .eq(SipRegistrationBlock::getSipAccountId, sipAccountId)
            .eq(SipRegistrationBlock::getStatus, "ACTIVE")
            .set(SipRegistrationBlock::getStatus, "RELEASED")
            .set(SipRegistrationBlock::getReleasedAt, now)
            .set(SipRegistrationBlock::getReleasedBy, LoginHelper.getUserId())
            .set(SipRegistrationBlock::getReleaseReason, defaultReason(reason, "管理员解除锁定")));
        if (updated == 0) throw new ServiceException("该分机当前没有有效锁定");
    }

    private void evaluateBlocks(SipRegistrationSecurityEvent event) {
        SipRegistrationSecurityPolicy policy = managementService.effectivePolicy(event.getNodeId());
        if (!Boolean.TRUE.equals(policy.getEnabled())) return;
        LocalDateTime occurredAt = event.getOccurredAt();
        Long accountId = event.getSipAccountId();
        String sourceIp = event.getSourceIp();
        if (accountId != null && sourceIp != null
            && continuousFailureCount(accountId, event.getNodeId(), sourceIp,
                occurredAt.minusSeconds(policy.getPairWindowSeconds())) >= policy.getPairFailureThreshold()) {
            createAutoBlock(event.getNodeId(), accountId, sourceIp, "IP_ACCOUNT", policy,
                "PAIR_FAILURE_THRESHOLD", "同一来源对该分机连续注册失败达到阈值");
        }
        if (accountId != null
            && continuousFailureCount(accountId, null, null,
                occurredAt.minusSeconds(policy.getAccountWindowSeconds())) >= policy.getAccountFailureThreshold()) {
            createAutoBlock(event.getNodeId(), accountId, null, "ACCOUNT", policy,
                "ACCOUNT_FAILURE_THRESHOLD", "该分机跨来源连续注册失败达到阈值");
        }
        if (sourceIp != null && !managementService.isIpAllowlisted(event.getNodeId(), sourceIp)
            && continuousFailureCount(null, event.getNodeId(), sourceIp,
                occurredAt.minusSeconds(policy.getIpWindowSeconds())) >= policy.getIpFailureThreshold()) {
            createAutoBlock(event.getNodeId(), null, sourceIp, "IP", policy,
                "IP_FAILURE_THRESHOLD", "该来源注册失败达到阈值");
        }
    }

    private long continuousFailureCount(Long accountId, Long nodeId, String sourceIp, LocalDateTime windowStart) {
        LambdaQueryWrapper<SipRegistrationSecurityEvent> successQuery = new LambdaQueryWrapper<SipRegistrationSecurityEvent>()
            .eq(accountId != null, SipRegistrationSecurityEvent::getSipAccountId, accountId)
            .eq(nodeId != null, SipRegistrationSecurityEvent::getNodeId, nodeId)
            .eq(sourceIp != null, SipRegistrationSecurityEvent::getSourceIp, sourceIp)
            .eq(SipRegistrationSecurityEvent::getResult, "SUCCESS")
            .ge(SipRegistrationSecurityEvent::getOccurredAt, windowStart)
            .orderByDesc(SipRegistrationSecurityEvent::getOccurredAt)
            .last("LIMIT 1");
        SipRegistrationSecurityEvent success = eventMapper.selectOne(successQuery);
        LocalDateTime effectiveStart = success != null && success.getOccurredAt().isAfter(windowStart)
            ? success.getOccurredAt() : windowStart;
        return eventMapper.selectCount(new LambdaQueryWrapper<SipRegistrationSecurityEvent>()
            .eq(accountId != null, SipRegistrationSecurityEvent::getSipAccountId, accountId)
            .eq(nodeId != null, SipRegistrationSecurityEvent::getNodeId, nodeId)
            .eq(sourceIp != null, SipRegistrationSecurityEvent::getSourceIp, sourceIp)
            .eq(SipRegistrationSecurityEvent::getResult, "FAILURE")
            .gt(SipRegistrationSecurityEvent::getOccurredAt, effectiveStart));
    }

    private void createAutoBlock(Long nodeId, Long accountId, String sourceIp, String type,
                                 SipRegistrationSecurityPolicy policy,
                                 String reasonCode, String reasonDetail) {
        if (hasActiveBlock(nodeId, accountId, sourceIp, type)) return;
        int previousTriggers = managementService.historicalTriggerCount(nodeId, accountId, sourceIp, type,
            LocalDateTime.now().minusDays(30));
        int seconds = previousTriggers == 0 ? policy.getFirstBlockSeconds()
            : previousTriggers == 1 ? policy.getSecondBlockSeconds() : policy.getRepeatedBlockSeconds();
        SipRegistrationBlock block = createBlock(nodeId, accountId, sourceIp, type,
            LocalDateTime.now().plusSeconds(seconds), reasonCode, reasonDetail, previousTriggers + 1);
        if ("IP".equals(type)) managementService.enqueueIpAction(block, "BLOCK_IP");
        managementService.notifyBlock(block, false);
        log.warn("SIP注册安全策略已自动封禁，type={}，nodeId={}，sipAccountId={}，sourceIp={}，seconds={}",
            type, nodeId, accountId, sourceIp, seconds);
    }

    private SipRegistrationBlock createBlock(Long nodeId, Long accountId, String sourceIp, String type, LocalDateTime expiresAt,
                                             String reasonCode, String reasonDetail, int triggerCount) {
        SipRegistrationBlock block = new SipRegistrationBlock();
        block.setNodeId(nodeId);
        block.setSipAccountId(accountId);
        block.setSourceIp(sourceIp);
        block.setBlockType(type);
        block.setStatus("ACTIVE");
        block.setTriggerCount(triggerCount);
        block.setBlockedAt(LocalDateTime.now());
        block.setExpiresAt(expiresAt);
        block.setReasonCode(reasonCode);
        block.setReasonDetail(reasonDetail);
        block.setExecutionStatus("IP".equals(type) ? "PENDING" : "NOT_REQUIRED");
        blockMapper.insert(block);
        return block;
    }

    private boolean hasActiveBlock(Long nodeId, Long accountId, String sourceIp, String type) {
        return blockMapper.exists(new LambdaQueryWrapper<SipRegistrationBlock>()
            .eq(nodeId != null, SipRegistrationBlock::getNodeId, nodeId)
            .eq(accountId != null, SipRegistrationBlock::getSipAccountId, accountId)
            .eq(sourceIp != null, SipRegistrationBlock::getSourceIp, sourceIp)
            .eq(SipRegistrationBlock::getBlockType, type)
            .eq(SipRegistrationBlock::getStatus, "ACTIVE")
            .and(wrapper -> wrapper.isNull(SipRegistrationBlock::getExpiresAt)
                .or().gt(SipRegistrationBlock::getExpiresAt, LocalDateTime.now())));
    }

    private LambdaQueryWrapper<SipRegistrationBlock> activeAccountOrPairBlockQuery(Long nodeId, Long accountId, String sourceIp) {
        LambdaQueryWrapper<SipRegistrationBlock> query = new LambdaQueryWrapper<SipRegistrationBlock>()
            .eq(nodeId != null, SipRegistrationBlock::getNodeId, nodeId)
            .eq(SipRegistrationBlock::getStatus, "ACTIVE")
            .and(wrapper -> wrapper.isNull(SipRegistrationBlock::getExpiresAt)
                .or().gt(SipRegistrationBlock::getExpiresAt, LocalDateTime.now()));
        return query.and(wrapper -> {
            wrapper.nested(account -> account.eq(SipRegistrationBlock::getBlockType, "ACCOUNT")
                .eq(SipRegistrationBlock::getSipAccountId, accountId));
            if (sourceIp != null) {
                wrapper.or(pair -> pair.eq(SipRegistrationBlock::getBlockType, "IP_ACCOUNT")
                    .eq(SipRegistrationBlock::getSipAccountId, accountId)
                    .eq(SipRegistrationBlock::getSourceIp, sourceIp));
            }
        });
    }

    private LambdaQueryWrapper<SipRegistrationBlock> activeIpBlockQuery(Long nodeId, String sourceIp) {
        return new LambdaQueryWrapper<SipRegistrationBlock>()
            .eq(SipRegistrationBlock::getNodeId, nodeId)
            .eq(SipRegistrationBlock::getBlockType, "IP")
            .eq(SipRegistrationBlock::getSourceIp, sourceIp)
            .eq(SipRegistrationBlock::getStatus, "ACTIVE")
            .and(wrapper -> wrapper.isNull(SipRegistrationBlock::getExpiresAt)
                .or().gt(SipRegistrationBlock::getExpiresAt, LocalDateTime.now()));
    }

    private SipAccount resolveAccount(Long nodeId, String extension, String authUsername) {
        if ((extension == null || extension.isBlank()) && (authUsername == null || authUsername.isBlank())) return null;
        return accountMapper.selectOne(new LambdaQueryWrapper<SipAccount>()
            .eq(SipAccount::getNodeId, nodeId)
            .and(wrapper -> {
                if (extension != null && !extension.isBlank()) wrapper.eq(SipAccount::getExtension, extension);
                if (authUsername != null && !authUsername.isBlank()) {
                    if (extension != null && !extension.isBlank()) wrapper.or();
                    wrapper.eq(SipAccount::getAuthUsername, authUsername);
                }
            }).last("LIMIT 1"));
    }

    private SipAccount requireAccount(Long sipAccountId) {
        SipAccount account = accountMapper.selectById(sipAccountId);
        if (account == null) throw new ServiceException("SIP 分机不存在");
        return account;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalizeUpper(String value) {
        String normalized = normalize(value);
        return normalized == null ? null : normalized.toUpperCase();
    }

    private String defaultReason(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
