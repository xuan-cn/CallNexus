package org.dromara.resource.sip.security.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.core.service.UserService;
import org.dromara.common.sse.dto.SseMessageDto;
import org.dromara.common.sse.utils.SseMessageUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.acl.domain.FreeSwitchAclEntry;
import org.dromara.resource.acl.domain.FreeSwitchAclSnapshot;
import org.dromara.resource.acl.domain.FreeSwitchAclVersion;
import org.dromara.resource.acl.mapper.FreeSwitchAclVersionMapper;
import org.dromara.resource.acl.service.IpCidrMatcher;
import org.dromara.resource.node.domain.FreeSwitchNode;
import org.dromara.resource.node.mapper.FreeSwitchNodeMapper;
import org.dromara.resource.sip.security.domain.SipRegistrationBlock;
import org.dromara.resource.sip.security.domain.SipRegistrationExecutionTask;
import org.dromara.resource.sip.security.domain.SipRegistrationSecurityEvent;
import org.dromara.resource.sip.security.domain.SipRegistrationSecurityPolicy;
import org.dromara.resource.sip.security.domain.request.SipSecurityBlockPageQuery;
import org.dromara.resource.sip.security.domain.request.SipSecurityEventPageQuery;
import org.dromara.resource.sip.security.domain.request.SipSecurityExecutionResultRequest;
import org.dromara.resource.sip.security.domain.request.SipSecurityPolicySaveRequest;
import org.dromara.resource.sip.security.domain.response.SipSecurityExecutionTaskResponse;
import org.dromara.resource.sip.security.mapper.SipRegistrationBlockMapper;
import org.dromara.resource.sip.security.mapper.SipRegistrationExecutionTaskMapper;
import org.dromara.resource.sip.security.mapper.SipRegistrationSecurityEventMapper;
import org.dromara.resource.sip.security.mapper.SipRegistrationSecurityPolicyMapper;
import org.dromara.resource.sip.security.service.SipRegistrationSecurityManagementService;
import org.dromara.system.domain.SysRole;
import org.dromara.system.mapper.SysRoleMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SipRegistrationSecurityManagementServiceImpl implements SipRegistrationSecurityManagementService {
    private final SipRegistrationSecurityEventMapper eventMapper;
    private final SipRegistrationBlockMapper blockMapper;
    private final SipRegistrationSecurityPolicyMapper policyMapper;
    private final SipRegistrationExecutionTaskMapper executionTaskMapper;
    private final FreeSwitchAclVersionMapper aclVersionMapper;
    private final FreeSwitchNodeMapper nodeMapper;
    private final SysRoleMapper roleMapper;
    private final UserService userService;

    @Override
    public TableDataInfo<SipRegistrationSecurityEvent> pageEvents(SipSecurityEventPageQuery query, PageQuery pageQuery) {
        Page<SipRegistrationSecurityEvent> page = eventMapper.selectPage(pageQuery.build(),
            new LambdaQueryWrapper<SipRegistrationSecurityEvent>()
                .eq(query.getNodeId() != null, SipRegistrationSecurityEvent::getNodeId, query.getNodeId())
                .like(hasText(query.getExtension()), SipRegistrationSecurityEvent::getExtensionSnapshot, query.getExtension())
                .eq(hasText(query.getSourceIp()), SipRegistrationSecurityEvent::getSourceIp, query.getSourceIp())
                .eq(hasText(query.getResult()), SipRegistrationSecurityEvent::getResult, upper(query.getResult()))
                .eq(hasText(query.getRiskLevel()), SipRegistrationSecurityEvent::getRiskLevel, upper(query.getRiskLevel()))
                .ge(query.getBeginTime() != null, SipRegistrationSecurityEvent::getOccurredAt, query.getBeginTime())
                .le(query.getEndTime() != null, SipRegistrationSecurityEvent::getOccurredAt, query.getEndTime())
                .orderByDesc(SipRegistrationSecurityEvent::getOccurredAt));
        return new TableDataInfo<>(page.getRecords(), page.getTotal());
    }

    @Override
    public TableDataInfo<SipRegistrationBlock> pageBlocks(SipSecurityBlockPageQuery query, PageQuery pageQuery) {
        Page<SipRegistrationBlock> page = blockMapper.selectPage(pageQuery.build(),
            new LambdaQueryWrapper<SipRegistrationBlock>()
                .eq(query.getNodeId() != null, SipRegistrationBlock::getNodeId, query.getNodeId())
                .eq(hasText(query.getBlockType()), SipRegistrationBlock::getBlockType, upper(query.getBlockType()))
                .eq(hasText(query.getSourceIp()), SipRegistrationBlock::getSourceIp, query.getSourceIp())
                .eq(hasText(query.getStatus()), SipRegistrationBlock::getStatus, upper(query.getStatus()))
                .orderByDesc(SipRegistrationBlock::getBlockedAt));
        return new TableDataInfo<>(page.getRecords(), page.getTotal());
    }

    @Override
    public List<SipRegistrationSecurityPolicy> listPolicies() {
        return policyMapper.selectList(new LambdaQueryWrapper<SipRegistrationSecurityPolicy>()
            .orderByAsc(SipRegistrationSecurityPolicy::getNodeId));
    }

    @Override
    public SipRegistrationSecurityPolicy effectivePolicy(Long nodeId) {
        SipRegistrationSecurityPolicy policy = null;
        if (nodeId != null) {
            policy = policyMapper.selectOne(new LambdaQueryWrapper<SipRegistrationSecurityPolicy>()
                .eq(SipRegistrationSecurityPolicy::getNodeId, nodeId).last("LIMIT 1"));
        }
        if (policy == null) {
            policy = policyMapper.selectOne(new LambdaQueryWrapper<SipRegistrationSecurityPolicy>()
                .eq(SipRegistrationSecurityPolicy::getNodeId, 0L).last("LIMIT 1"));
        }
        return policy == null ? defaultPolicy(nodeId == null ? 0L : nodeId) : policy;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long savePolicy(SipSecurityPolicySaveRequest request) {
        validatePolicy(request);
        SipRegistrationSecurityPolicy policy = policyMapper.selectOne(new LambdaQueryWrapper<SipRegistrationSecurityPolicy>()
            .eq(SipRegistrationSecurityPolicy::getNodeId, request.getNodeId()).last("LIMIT 1"));
        if (policy == null) {
            policy = new SipRegistrationSecurityPolicy();
            apply(policy, request);
            policy.setVersion(0);
            policyMapper.insert(policy);
        } else {
            apply(policy, request);
            if (request.getVersion() != null) policy.setVersion(request.getVersion());
            if (policyMapper.updateById(policy) != 1) throw new ServiceException("安全策略已被其他用户修改，请刷新后重试");
        }
        return policy.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void releaseBlock(Long blockId, String reason) {
        SipRegistrationBlock block = blockMapper.selectById(blockId);
        if (block == null || !"ACTIVE".equals(block.getStatus())) throw new ServiceException("封禁不存在或已解除");
        block.setStatus("RELEASED");
        block.setReleasedAt(LocalDateTime.now());
        block.setReleaseReason(hasText(reason) ? reason.trim() : "管理员解除封禁");
        blockMapper.updateById(block);
        if ("IP".equals(block.getBlockType())) enqueueIpAction(block, "UNBLOCK_IP");
    }

    @Override
    public boolean isIpAllowlisted(Long nodeId, String sourceIp) {
        if (nodeId == null || !hasText(sourceIp)) return false;
        List<FreeSwitchAclVersion> versions = aclVersionMapper.selectList(new LambdaQueryWrapper<FreeSwitchAclVersion>()
            .eq(FreeSwitchAclVersion::getNodeId, nodeId)
            .eq(FreeSwitchAclVersion::getCurrentVersion, true));
        for (FreeSwitchAclVersion version : versions) {
            FreeSwitchAclSnapshot snapshot = JsonUtils.parseObject(version.getSnapshotJson(), FreeSwitchAclSnapshot.class);
            if (snapshot == null || !Boolean.TRUE.equals(snapshot.enabled()) || !"SIP_ENDPOINT".equals(snapshot.purpose())) continue;
            for (FreeSwitchAclEntry entry : snapshot.entries() == null ? List.<FreeSwitchAclEntry>of() : snapshot.entries()) {
                if ("ALLOW".equalsIgnoreCase(entry.action()) && IpCidrMatcher.matches(sourceIp, entry.cidr())) return true;
            }
        }
        return false;
    }

    @Override
    public void enqueueIpAction(SipRegistrationBlock block, String action) {
        if (block == null || block.getNodeId() == null || !hasText(block.getSourceIp())) return;
        String key = action + ":" + block.getId();
        if (executionTaskMapper.exists(new LambdaQueryWrapper<SipRegistrationExecutionTask>()
            .eq(SipRegistrationExecutionTask::getIdempotencyKey, key))) return;
        SipRegistrationExecutionTask task = new SipRegistrationExecutionTask();
        task.setNodeId(block.getNodeId());
        task.setBlockId(block.getId());
        task.setAction(action);
        task.setSourceIp(block.getSourceIp());
        task.setExpiresAt(block.getExpiresAt());
        task.setStatus("PENDING");
        task.setRetryCount(0);
        task.setIdempotencyKey(key);
        executionTaskMapper.insert(task);
        block.setExecutionStatus("PENDING");
        blockMapper.updateById(block);
    }

    @Override
    public void notifyBlock(SipRegistrationBlock block, boolean executionFailure) {
        SipRegistrationSecurityPolicy policy = effectivePolicy(block.getNodeId());
        if (!Boolean.TRUE.equals(policy.getAlertEnabled())) return;
        try {
            List<Long> roleIds = roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                    .in(SysRole::getRoleKey, List.of("superadmin", "admin", "cc_supervisor"))
                    .eq(SysRole::getDelFlag, "0"))
                .stream().map(SysRole::getRoleId).toList();
            List<Long> userIds = userService.selectUserIdsByRoleIds(roleIds).stream().distinct().toList();
            if (userIds.isEmpty()) return;
            String message = executionFailure
                ? "SIP来源IP【" + block.getSourceIp() + "】封禁任务连续执行失败，请检查节点Agent。"
                : "SIP注册安全策略已触发：" + block.getReasonDetail();
            SseMessageDto dto = new SseMessageDto();
            dto.setUserIds(userIds);
            dto.setMessage(JsonUtils.toJsonString(Map.of(
                "type", "SIP_REGISTRATION_SECURITY_ALERT",
                "eventId", "sip-security:" + block.getId() + (executionFailure ? ":execution-failed" : ":blocked"),
                "blockId", block.getId().toString(),
                "nodeId", block.getNodeId().toString(),
                "message", message,
                "executionFailure", executionFailure
            )));
            SseMessageUtils.publishMessage(dto);
        } catch (Exception ignored) {
            // 安全决策不能因通知通道异常而回滚。
        }
    }

    @Override
    public SipSecurityExecutionTaskResponse claim(String nodeCode, String token) {
        FreeSwitchNode node = authenticate(nodeCode, token);
        return TenantHelper.dynamic(node.getTenantId(), () -> claimForNode(node.getId()));
    }

    @Override
    public void report(Long taskId, String nodeCode, String token, SipSecurityExecutionResultRequest request) {
        FreeSwitchNode node = authenticate(nodeCode, token);
        TenantHelper.dynamic(node.getTenantId(), () -> reportForNode(taskId, node.getId(), request));
    }

    @Override
    public int historicalTriggerCount(Long nodeId, Long accountId, String sourceIp, String blockType, LocalDateTime since) {
        return Math.toIntExact(blockMapper.selectCount(new LambdaQueryWrapper<SipRegistrationBlock>()
            .eq(SipRegistrationBlock::getNodeId, nodeId)
            .eq(accountId != null, SipRegistrationBlock::getSipAccountId, accountId)
            .eq(sourceIp != null, SipRegistrationBlock::getSourceIp, sourceIp)
            .eq(SipRegistrationBlock::getBlockType, blockType)
            .ge(SipRegistrationBlock::getBlockedAt, since)));
    }

    @Scheduled(fixedDelay = 30000)
    public void expireBlocks() {
        LocalDateTime now = LocalDateTime.now();
        List<SipRegistrationBlock> blocks = TenantHelper.ignore(() -> blockMapper.selectList(
            new LambdaQueryWrapper<SipRegistrationBlock>()
                .eq(SipRegistrationBlock::getStatus, "ACTIVE")
                .isNotNull(SipRegistrationBlock::getExpiresAt)
                .le(SipRegistrationBlock::getExpiresAt, now)
                .last("LIMIT 200")));
        for (SipRegistrationBlock candidate : blocks) {
            TenantHelper.dynamic(candidate.getTenantId(), () -> expire(candidate.getId()));
        }
    }

    private void expire(Long blockId) {
        SipRegistrationBlock block = blockMapper.selectById(blockId);
        if (block == null || !"ACTIVE".equals(block.getStatus()) || block.getExpiresAt() == null
            || block.getExpiresAt().isAfter(LocalDateTime.now())) return;
        block.setStatus("EXPIRED");
        blockMapper.updateById(block);
        if ("IP".equals(block.getBlockType())) enqueueIpAction(block, "UNBLOCK_IP");
    }

    private SipSecurityExecutionTaskResponse claimForNode(Long nodeId) {
        LocalDateTime now = LocalDateTime.now();
        SipRegistrationExecutionTask task = executionTaskMapper.selectOne(new LambdaQueryWrapper<SipRegistrationExecutionTask>()
            .eq(SipRegistrationExecutionTask::getNodeId, nodeId)
            .and(w -> w.eq(SipRegistrationExecutionTask::getStatus, "PENDING")
                .or(x -> x.eq(SipRegistrationExecutionTask::getStatus, "FAILED")
                    .lt(SipRegistrationExecutionTask::getRetryCount, 5).le(SipRegistrationExecutionTask::getNextRetryAt, now))
                .or(x -> x.eq(SipRegistrationExecutionTask::getStatus, "PROCESSING")
                    .lt(SipRegistrationExecutionTask::getLeaseExpiresAt, now)))
            .orderByAsc(SipRegistrationExecutionTask::getCreateTime).last("LIMIT 1"));
        if (task == null) return null;
        task.setStatus("PROCESSING");
        task.setLeaseToken(UUID.randomUUID().toString().replace("-", ""));
        task.setLeaseExpiresAt(now.plusMinutes(2));
        executionTaskMapper.updateById(task);
        SipSecurityExecutionTaskResponse response = new SipSecurityExecutionTaskResponse();
        response.setTaskId(task.getId());
        response.setLeaseToken(task.getLeaseToken());
        response.setAction(task.getAction());
        response.setSourceIp(task.getSourceIp());
        response.setExpiresAt(task.getExpiresAt());
        response.setIdempotencyKey(task.getIdempotencyKey());
        return response;
    }

    private void reportForNode(Long taskId, Long nodeId, SipSecurityExecutionResultRequest request) {
        SipRegistrationExecutionTask task = executionTaskMapper.selectById(taskId);
        if (task == null || !nodeId.equals(task.getNodeId()) || !request.getLeaseToken().equals(task.getLeaseToken())
            || task.getLeaseExpiresAt() == null || task.getLeaseExpiresAt().isBefore(LocalDateTime.now())) {
            throw new ServiceException("注册安全执行任务租约无效");
        }
        task.setLeaseToken(null);
        task.setLeaseExpiresAt(null);
        if (Boolean.TRUE.equals(request.getSuccess())) {
            task.setStatus("SUCCESS");
            task.setFailureReason(null);
            task.setExecutedAt(LocalDateTime.now());
        } else {
            int retry = task.getRetryCount() == null ? 1 : task.getRetryCount() + 1;
            task.setStatus("FAILED");
            task.setRetryCount(retry);
            task.setFailureReason(limit(request.getFailureReason()));
            task.setNextRetryAt(retry >= 5 ? null : LocalDateTime.now().plusSeconds(Math.min(1800, 30L << Math.min(retry - 1, 6))));
        }
        executionTaskMapper.updateById(task);
        if (task.getBlockId() != null) {
            blockMapper.update(null, new LambdaUpdateWrapper<SipRegistrationBlock>()
                .eq(SipRegistrationBlock::getId, task.getBlockId())
                .set(SipRegistrationBlock::getExecutionStatus, task.getStatus()));
            if ("FAILED".equals(task.getStatus()) && task.getRetryCount() != null && task.getRetryCount() >= 5) {
                SipRegistrationBlock block = blockMapper.selectById(task.getBlockId());
                if (block != null) notifyBlock(block, true);
            }
        }
    }

    private FreeSwitchNode authenticate(String nodeCode, String token) {
        if (!hasText(nodeCode) || !hasText(token)) throw new ServiceException("节点安全 Agent 鉴权失败");
        String hash = sha256(token);
        FreeSwitchNode node = TenantHelper.ignore(() -> nodeMapper.selectOne(new LambdaQueryWrapper<FreeSwitchNode>()
            .eq(FreeSwitchNode::getNodeCode, nodeCode)
            .eq(FreeSwitchNode::getAgentTokenHash, hash)
            .eq(FreeSwitchNode::getAgentEnabled, true)
            .eq(FreeSwitchNode::getEnabled, true).last("LIMIT 1")));
        if (node == null) throw new ServiceException("节点安全 Agent 鉴权失败");
        return node;
    }

    private void validatePolicy(SipSecurityPolicySaveRequest request) {
        if (request.getNodeId() < 0) throw new ServiceException("节点ID不能小于0");
        if (request.getNodeId() > 0 && nodeMapper.selectById(request.getNodeId()) == null) throw new ServiceException("FreeSWITCH节点不存在");
        if (request.getSecondBlockSeconds() < request.getFirstBlockSeconds()
            || request.getRepeatedBlockSeconds() < request.getSecondBlockSeconds()) {
            throw new ServiceException("阶梯封禁时间必须依次递增");
        }
    }

    private void apply(SipRegistrationSecurityPolicy policy, SipSecurityPolicySaveRequest request) {
        policy.setNodeId(request.getNodeId());
        policy.setEnabled(request.getEnabled());
        policy.setPairWindowSeconds(request.getPairWindowSeconds());
        policy.setPairFailureThreshold(request.getPairFailureThreshold());
        policy.setAccountWindowSeconds(request.getAccountWindowSeconds());
        policy.setAccountFailureThreshold(request.getAccountFailureThreshold());
        policy.setIpWindowSeconds(request.getIpWindowSeconds());
        policy.setIpFailureThreshold(request.getIpFailureThreshold());
        policy.setFirstBlockSeconds(request.getFirstBlockSeconds());
        policy.setSecondBlockSeconds(request.getSecondBlockSeconds());
        policy.setRepeatedBlockSeconds(request.getRepeatedBlockSeconds());
        policy.setAlertEnabled(request.getAlertEnabled());
    }

    private SipRegistrationSecurityPolicy defaultPolicy(Long nodeId) {
        SipRegistrationSecurityPolicy policy = new SipRegistrationSecurityPolicy();
        policy.setNodeId(nodeId);
        policy.setEnabled(true);
        policy.setPairWindowSeconds(60);
        policy.setPairFailureThreshold(5);
        policy.setAccountWindowSeconds(600);
        policy.setAccountFailureThreshold(10);
        policy.setIpWindowSeconds(300);
        policy.setIpFailureThreshold(20);
        policy.setFirstBlockSeconds(600);
        policy.setSecondBlockSeconds(1800);
        policy.setRepeatedBlockSeconds(86400);
        policy.setAlertEnabled(true);
        policy.setVersion(0);
        return policy;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new ServiceException("生成节点Token摘要失败");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String upper(String value) {
        return value == null ? null : value.trim().toUpperCase();
    }

    private String limit(String value) {
        if (value == null) return null;
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
