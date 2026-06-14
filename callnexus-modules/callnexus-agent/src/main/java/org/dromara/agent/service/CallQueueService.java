package org.dromara.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.agent.domain.CallQueue;
import org.dromara.agent.domain.Agent;
import org.dromara.agent.domain.AgentExtension;
import org.dromara.agent.domain.AgentPresence;
import org.dromara.agent.domain.AgentPresenceStatus;
import org.dromara.agent.domain.SkillGroup;
import org.dromara.agent.domain.SkillGroupMember;
import org.dromara.agent.domain.request.CallQueueRequest;
import org.dromara.agent.domain.response.CallQueueResponse;
import org.dromara.agent.mapper.AgentExtensionMapper;
import org.dromara.agent.mapper.AgentMapper;
import org.dromara.agent.mapper.CallQueueMapper;
import org.dromara.agent.mapper.SkillGroupMapper;
import org.dromara.agent.mapper.SkillGroupMemberMapper;
import org.dromara.agent.runtime.QueueAgentRuntimeConfig;
import org.dromara.agent.runtime.QueueNodeRuntimeConfig;
import org.dromara.agent.runtime.QueueRuntimeSyncResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.media.domain.MediaAsset;
import org.dromara.resource.media.domain.MediaNodeSync;
import org.dromara.resource.media.domain.MediaPublication;
import org.dromara.resource.media.mapper.MediaAssetMapper;
import org.dromara.resource.media.mapper.MediaNodeSyncMapper;
import org.dromara.resource.media.mapper.MediaPublicationMapper;
import org.dromara.resource.node.group.domain.FreeSwitchNodeGroup;
import org.dromara.resource.node.group.domain.FreeSwitchNodeGroupMember;
import org.dromara.resource.node.group.mapper.FreeSwitchNodeGroupMapper;
import org.dromara.resource.node.group.mapper.FreeSwitchNodeGroupMemberMapper;
import org.dromara.resource.sip.domain.response.SipAccountResponse;
import org.dromara.resource.sip.service.SipAccountQueryService;
import org.dromara.resource.queue.domain.response.CallQueueDialplanResponse;
import org.dromara.resource.queue.service.CallQueueQueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CallQueueService implements CallQueueQueryService {
    private static final Set<String> STRATEGIES = Set.of(
        "LONGEST_IDLE_AGENT", "ROUND_ROBIN", "TOP_DOWN", "RING_ALL"
    );

    private final CallQueueMapper queueMapper;
    private final SkillGroupMapper skillGroupMapper;
    private final SkillGroupMemberMapper skillGroupMemberMapper;
    private final AgentMapper agentMapper;
    private final AgentExtensionMapper agentExtensionMapper;
    private final FreeSwitchNodeGroupMapper nodeGroupMapper;
    private final FreeSwitchNodeGroupMemberMapper nodeGroupMemberMapper;
    private final MediaAssetMapper mediaAssetMapper;
    private final MediaNodeSyncMapper mediaNodeSyncMapper;
    private final MediaPublicationMapper mediaPublicationMapper;
    private final SipAccountQueryService sipAccountQueryService;
    private final CallQueueRuntimeSyncService runtimeSyncService;

    public List<CallQueueResponse> list() {
        return queueMapper.selectList(new LambdaQueryWrapper<CallQueue>().orderByAsc(CallQueue::getQueueCode))
            .stream().map(this::response).toList();
    }

    public CallQueueResponse get(Long id) {
        return response(require(id));
    }

    @Override
    public CallQueueDialplanResponse findAvailableQueue(String tenantId, Long queueId, Long nodeId) {
        if (queueId == null || nodeId == null) return null;
        return TenantHelper.dynamic(tenantId, () -> {
            CallQueue queue = queueMapper.selectById(queueId);
            if (queue == null || !Boolean.TRUE.equals(queue.getEnabled()) || !"SYNCED".equals(queue.getSyncStatus())) {
                return null;
            }
            boolean nodeMember = nodeGroupMemberMapper.exists(new LambdaQueryWrapper<FreeSwitchNodeGroupMember>()
                .eq(FreeSwitchNodeGroupMember::getGroupId, queue.getNodeGroupId())
                .eq(FreeSwitchNodeGroupMember::getNodeId, nodeId));
            if (!nodeMember) return null;
            CallQueueDialplanResponse response = new CallQueueDialplanResponse();
            response.setId(queue.getId());
            response.setQueueCode(queue.getQueueCode());
            response.setQueueName(queue.getQueueName());
            return response;
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(CallQueueRequest request) {
        ensureCode(request.getQueueCode(), null);
        validateReferences(request);
        CallQueue queue = new CallQueue();
        apply(queue, request);
        queue.setSyncStatus("NOT_SYNCED");
        queueMapper.insert(queue);
        return queue.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, CallQueueRequest request) {
        ensureCode(request.getQueueCode(), id);
        validateReferences(request);
        CallQueue queue = require(id);
        removePreviousRuntimeIfNecessary(queue, request);
        apply(queue, request);
        queue.setSyncStatus("NOT_SYNCED");
        queue.setSyncError(null);
        queue.setVersion(request.getVersion());
        if (queueMapper.updateById(queue) != 1) {
            throw new ServiceException("呼叫队列已被其他用户修改，请刷新后重试");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        CallQueue queue = require(id);
        runtimeSyncService.removeQueue(nodeIds(queue.getNodeGroupId()), queue.getQueueCode());
        if (queueMapper.deleteById(id) != 1) {
            throw new ServiceException("呼叫队列不存在");
        }
    }

    public void sync(Long id) {
        CallQueue queue = require(id);
        if (!Boolean.TRUE.equals(queue.getEnabled())) {
            throw new ServiceException("呼叫队列已停用，无法同步");
        }
        boolean resultRecorded = false;
        try {
            QueueRuntimeSyncResult result = runtimeSyncService.syncQueue(runtimeConfigs(queue));
            queue.setLastSyncedAt(LocalDateTime.now());
            queue.setSyncStatus(result.failedCount() == 0 ? "SYNCED" : result.successCount() > 0 ? "PARTIAL" : "FAILED");
            queue.setSyncError(result.errors().isEmpty() ? null : truncate(String.join("；", result.errors())));
            queueMapper.updateById(queue);
            resultRecorded = true;
            if (result.failedCount() > 0) {
                String prefix = result.successCount() > 0 ? "队列仅部分节点同步成功：" : "队列同步失败：";
                throw new ServiceException(prefix + truncate(String.join("；", result.errors())));
            }
        } catch (ServiceException exception) {
            if (!resultRecorded) {
                queue.setLastSyncedAt(LocalDateTime.now());
                queue.setSyncStatus("FAILED");
                queue.setSyncError(truncate(exception.getMessage()));
                queueMapper.updateById(queue);
            }
            throw exception;
        }
    }

    private void validateReferences(CallQueueRequest request) {
        if (!STRATEGIES.contains(request.getStrategy())) {
            throw new ServiceException("不支持的队列分配策略");
        }
        if (!"RING_ALL".equals(request.getStrategy())
            && request.getMaxWaitSeconds() <= request.getRingTimeoutSeconds()) {
            throw new ServiceException("逐个分配坐席时，队列最大等待时间必须大于单个坐席振铃超时时间");
        }
        SkillGroup skillGroup = skillGroupMapper.selectById(request.getSkillGroupId());
        if (skillGroup == null || !Boolean.TRUE.equals(skillGroup.getEnabled())) {
            throw new ServiceException("技能组不存在或已停用");
        }
        FreeSwitchNodeGroup nodeGroup = nodeGroupMapper.selectById(request.getNodeGroupId());
        if (nodeGroup == null || !Boolean.TRUE.equals(nodeGroup.getEnabled())) {
            throw new ServiceException("FreeSWITCH 节点组不存在或已停用");
        }
        if (request.getWaitMediaId() != null) {
            MediaAsset media = mediaAssetMapper.selectById(request.getWaitMediaId());
            if (media == null || !Boolean.TRUE.equals(media.getEnabled())
                || !"QUEUE_WAIT_MUSIC".equals(media.getCategory())
                || !"PUBLISHED".equals(media.getPublishStatus())) {
                throw new ServiceException("队列等待音不存在、未发布或分类不是队列等待音乐");
            }
        }
    }

    private CallQueue require(Long id) {
        CallQueue queue = queueMapper.selectById(id);
        if (queue == null) {
            throw new ServiceException("呼叫队列不存在");
        }
        return queue;
    }

    private void ensureCode(String code, Long excludedId) {
        if (queueMapper.exists(new LambdaQueryWrapper<CallQueue>()
            .eq(CallQueue::getQueueCode, code)
            .ne(excludedId != null, CallQueue::getId, excludedId))) {
            throw new ServiceException("队列编码已存在");
        }
    }

    private void apply(CallQueue queue, CallQueueRequest request) {
        queue.setQueueCode(request.getQueueCode());
        queue.setQueueName(request.getQueueName());
        queue.setNodeGroupId(request.getNodeGroupId());
        queue.setSkillGroupId(request.getSkillGroupId());
        queue.setStrategy(request.getStrategy());
        queue.setWaitMediaId(request.getWaitMediaId());
        queue.setMaxWaitSeconds(request.getMaxWaitSeconds());
        queue.setRingTimeoutSeconds(request.getRingTimeoutSeconds());
        queue.setMaxNoAnswer(request.getMaxNoAnswer());
        queue.setWrapUpSeconds(request.getWrapUpSeconds());
        queue.setEnabled(request.getEnabled());
        queue.setRemark(request.getRemark());
    }

    private void removePreviousRuntimeIfNecessary(CallQueue queue, CallQueueRequest request) {
        boolean runtimeMayExist = "SYNCED".equals(queue.getSyncStatus()) || "PARTIAL".equals(queue.getSyncStatus());
        boolean identityChanged = !Objects.equals(queue.getQueueCode(), request.getQueueCode())
            || !Objects.equals(queue.getNodeGroupId(), request.getNodeGroupId());
        if (runtimeMayExist && (identityChanged || !Boolean.TRUE.equals(request.getEnabled()))) {
            runtimeSyncService.removeQueue(nodeIds(queue.getNodeGroupId()), queue.getQueueCode());
        }
    }

    private CallQueueResponse response(CallQueue queue) {
        CallQueueResponse response = new CallQueueResponse();
        response.setId(queue.getId());
        response.setQueueCode(queue.getQueueCode());
        response.setQueueName(queue.getQueueName());
        response.setNodeGroupId(queue.getNodeGroupId());
        FreeSwitchNodeGroup nodeGroup = nodeGroupMapper.selectById(queue.getNodeGroupId());
        response.setNodeGroupName(nodeGroup == null ? null : nodeGroup.getGroupName());
        response.setNodeIds(nodeIds(queue.getNodeGroupId()));
        response.setSkillGroupId(queue.getSkillGroupId());
        SkillGroup skillGroup = skillGroupMapper.selectById(queue.getSkillGroupId());
        response.setSkillGroupName(skillGroup == null ? null : skillGroup.getGroupName());
        response.setStrategy(queue.getStrategy());
        response.setWaitMediaId(queue.getWaitMediaId());
        response.setMaxWaitSeconds(queue.getMaxWaitSeconds());
        response.setRingTimeoutSeconds(queue.getRingTimeoutSeconds());
        response.setMaxNoAnswer(queue.getMaxNoAnswer());
        response.setWrapUpSeconds(queue.getWrapUpSeconds());
        response.setSyncStatus(queue.getSyncStatus());
        response.setLastSyncedAt(queue.getLastSyncedAt());
        response.setSyncError(queue.getSyncError());
        response.setEnabled(queue.getEnabled());
        response.setRemark(queue.getRemark());
        response.setVersion(queue.getVersion());
        response.setCreateTime(queue.getCreateTime());
        return response;
    }

    private List<QueueNodeRuntimeConfig> runtimeConfigs(CallQueue queue) {
        List<SkillGroupMember> members = skillGroupMemberMapper.selectList(new LambdaQueryWrapper<SkillGroupMember>()
            .eq(SkillGroupMember::getSkillGroupId, queue.getSkillGroupId())
            .orderByAsc(SkillGroupMember::getPriority));
        List<QueueNodeRuntimeConfig> configs = new ArrayList<>();
        for (Long nodeId : nodeIds(queue.getNodeGroupId())) {
            List<QueueAgentRuntimeConfig> agents = new ArrayList<>();
            for (SkillGroupMember member : members) {
                Agent agent = agentMapper.selectById(member.getAgentId());
                if (agent == null || !Boolean.TRUE.equals(agent.getEnabled())) continue;
                AgentExtension binding = agentExtensionMapper.selectOne(new LambdaQueryWrapper<AgentExtension>()
                    .eq(AgentExtension::getAgentId, agent.getId()));
                if (binding == null) continue;
                SipAccountResponse sipAccount = sipAccountQueryService.get(binding.getSipAccountId());
                if (sipAccount == null || !Boolean.TRUE.equals(sipAccount.getEnabled()) || !nodeId.equals(sipAccount.getNodeId())) continue;
                agents.add(new QueueAgentRuntimeConfig(agent.getId(), agent.getAgentName(), sipAccount.getExtension(),
                    sipAccount.getDomain(), member.getSkillLevel(), member.getPriority(), queue.getRingTimeoutSeconds(),
                    queue.getMaxNoAnswer(), queue.getWrapUpSeconds(), presenceStatus(agent.getId())));
            }
            if (agents.isEmpty()) {
                throw new ServiceException("节点 " + nodeId + " 没有绑定可用 SIP 分机的技能组坐席");
            }
            configs.add(new QueueNodeRuntimeConfig(nodeId, queue.getQueueCode(), queue.getStrategy(),
                waitMediaPath(queue, nodeId), queue.getMaxWaitSeconds(), agents));
        }
        if (configs.isEmpty()) {
            throw new ServiceException("队列关联的 FreeSWITCH 节点组没有成员节点");
        }
        return configs;
    }

    private List<Long> nodeIds(Long nodeGroupId) {
        return nodeGroupMemberMapper.selectList(new LambdaQueryWrapper<FreeSwitchNodeGroupMember>()
                .eq(FreeSwitchNodeGroupMember::getGroupId, nodeGroupId))
            .stream().map(FreeSwitchNodeGroupMember::getNodeId).distinct().toList();
    }

    private String waitMediaPath(CallQueue queue, Long nodeId) {
        if (queue.getWaitMediaId() == null) return null;
        MediaAsset media = mediaAssetMapper.selectById(queue.getWaitMediaId());
        List<Long> activePublicationIds = mediaPublicationMapper.selectList(new LambdaQueryWrapper<MediaPublication>()
                .eq(MediaPublication::getMediaId, queue.getWaitMediaId())
                .eq(MediaPublication::getVersionId, media.getLatestVersionId())
                .in(MediaPublication::getStatus, List.of("PUBLISHING", "PARTIAL", "PUBLISHED")))
            .stream().map(MediaPublication::getId).toList();
        if (activePublicationIds.isEmpty()) {
            throw new ServiceException("队列等待音当前版本没有有效发布记录");
        }
        MediaNodeSync sync = mediaNodeSyncMapper.selectOne(new LambdaQueryWrapper<MediaNodeSync>()
            .eq(MediaNodeSync::getMediaId, queue.getWaitMediaId())
            .in(MediaNodeSync::getPublicationId, activePublicationIds)
            .eq(MediaNodeSync::getNodeId, nodeId)
            .eq(MediaNodeSync::getStatus, "SUCCESS")
            .orderByDesc(MediaNodeSync::getSyncedAt)
            .last("limit 1"));
        if (sync == null) {
            throw new ServiceException("队列等待音尚未同步到节点 " + nodeId);
        }
        return sync.getTargetPath();
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 1000) return value;
        return value.substring(0, 1000);
    }

    private AgentPresenceStatus presenceStatus(Long agentId) {
        AgentPresence presence = RedisUtils.getCacheObject("callnexus:agent:presence:" + LoginHelper.getTenantId() + ":" + agentId);
        return presence == null ? AgentPresenceStatus.OFFLINE : presence.getStatus();
    }
}
