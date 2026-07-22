package org.dromara.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import org.dromara.ai.service.AiGeneratedMediaQueryService;
import org.dromara.ai.service.impl.AiSpeechApplicationServiceImpl;
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
@Slf4j
public class CallQueueService implements CallQueueQueryService {
    private static final Set<String> STRATEGIES = Set.of(
        "LONGEST_IDLE_AGENT", "ROUND_ROBIN", "TOP_DOWN", "RING_ALL"
    );
    private static final Set<String> ANSWER_ACTIONS = Set.of("NONE", "PLAY_AGENT_NUMBER", "PLAY_MEDIA");
    private static final Set<String> HANGUP_KEY_ACTIONS = Set.of("NONE", "AGENT", "CALLER");
    private static final Set<String> EXIT_ACTIONS = Set.of("HANGUP", "CONTINUE", "VOICEMAIL", "IVR", "EXTENSION", "QUEUE");
    private static final Set<String> NO_AGENT_ACTIONS = Set.of("WAIT", "HANGUP", "VOICEMAIL", "IVR", "EXTENSION", "QUEUE");
    private static final Set<String> AGENT_NO_ANSWER_ACTIONS = Set.of("NEXT_AGENT", "BREAK_AGENT");

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
    private final StickyAgentRegistry stickyAgentRegistry;
    private final AiGeneratedMediaQueryService generatedMediaQueryService;

    public List<CallQueueResponse> list() {
        return queueMapper.selectList(new LambdaQueryWrapper<CallQueue>().orderByAsc(CallQueue::getQueueCode))
            .stream().map(this::response).toList();
    }

    public CallQueueResponse get(Long id) {
        return response(require(id));
    }

    @Override
    public CallQueueDialplanResponse findAvailableQueue(String tenantId, Long queueId, Long nodeId, String callerNumber) {
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
            fillDialplanOptions(response, queue, nodeId);
            fillStickyAgentTarget(response, queue, tenantId, callerNumber, nodeId);
            fillMobileTransferOptions(response, queue, tenantId, nodeId);
            return response;
        });
    }

    @Override
    public void refreshQueueAgentRuntimeStatus(String tenantId, Long queueId, Long nodeId) {
        if (queueId == null || nodeId == null) {
            return;
        }
        TenantHelper.dynamic(tenantId, () -> {
            CallQueue queue = queueMapper.selectById(queueId);
            if (queue == null || !Boolean.TRUE.equals(queue.getEnabled()) || !"SYNCED".equals(queue.getSyncStatus())) {
                return null;
            }
            boolean nodeMember = nodeGroupMemberMapper.exists(new LambdaQueryWrapper<FreeSwitchNodeGroupMember>()
                .eq(FreeSwitchNodeGroupMember::getGroupId, queue.getNodeGroupId())
                .eq(FreeSwitchNodeGroupMember::getNodeId, nodeId));
            if (!nodeMember) {
                return null;
            }
            try {
                runtimeSyncService.syncQueueAgentStatuses(List.of(runtimeAgentStatusConfig(queue, nodeId)));
            } catch (Exception exception) {
                log.warn("Refresh queue agent runtime status failed before queue entry, tenantId={}, queueId={}, nodeId={}, error={}",
                    tenantId, queueId, nodeId, exception.getMessage());
            }
            return null;
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
        validateEnhancedOptions(request);
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
            validatePublishedMedia(request.getWaitMediaId(), "QUEUE_WAIT_MUSIC", "队列等待音不存在、未发布或分类不是队列等待音乐");
        }
        if (request.getForceWaitMediaId() != null) {
            validatePublishedMedia(request.getForceWaitMediaId(), "QUEUE_WAIT_MUSIC", "入队前提示音不存在、未发布或分类不是队列等待音");
        }
        if (request.getQueueAnnounceMediaId() != null) {
            validatePublishedMedia(request.getQueueAnnounceMediaId(), "QUEUE_WAIT_MUSIC", "排队提醒音不存在、未发布或分类不是队列等待音乐");
        }
        if ("PLAY_MEDIA".equals(request.getAnswerAction())) {
            if (request.getAnswerMediaId() == null) {
                throw new ServiceException("接通时播放语音必须选择媒体");
            }
            validatePublishedMedia(request.getAnswerMediaId(), "IVR_PROMPT", "接通提示音不存在、未发布或分类不是 IVR 提示音");
        }
        if (Boolean.TRUE.equals(request.getSatisfactionEnabled())) {
            if (request.getSatisfactionMediaId() == null) {
                throw new ServiceException("启用满意度评价时必须选择评价提示音");
            }
            validatePublishedMedia(request.getSatisfactionMediaId(), "IVR_PROMPT",
                "满意度评价提示音不存在、未发布或分类不是 IVR 提示音");
        }
    }

    private void validateEnhancedOptions(CallQueueRequest request) {
        if (!ANSWER_ACTIONS.contains(request.getAnswerAction())) {
            throw new ServiceException("不支持的接通时动作");
        }
        if (!HANGUP_KEY_ACTIONS.contains(request.getHangupKeyAction())) {
            throw new ServiceException("不支持的挂机按键采集方式");
        }
        if (!EXIT_ACTIONS.contains(request.getTimeoutAction())) {
            throw new ServiceException("不支持的队列超时处理方式");
        }
        if (!NO_AGENT_ACTIONS.contains(request.getNoAgentAction())) {
            throw new ServiceException("不支持的无坐席处理方式");
        }
        if (!AGENT_NO_ANSWER_ACTIONS.contains(request.getAgentNoAnswerAction())) {
            throw new ServiceException("不支持的坐席未接处理方式");
        }
        requireTarget("队列超时处理", request.getTimeoutAction(), request.getTimeoutTarget());
        requireTarget("无坐席处理", request.getNoAgentAction(), request.getNoAgentTarget());
        if (Boolean.TRUE.equals(request.getBusyTransferMobile()) && org.apache.commons.lang3.StringUtils.isBlank(request.getBusyTransferNumber())) {
            throw new ServiceException("启用遇忙转手机时必须填写手机号");
        }
        if (Boolean.TRUE.equals(request.getAgentTimeoutTransferMobile()) && org.apache.commons.lang3.StringUtils.isBlank(request.getAgentTimeoutTransferNumber())) {
            throw new ServiceException("启用坐席超时转手机时必须填写手机号");
        }
        if (Boolean.TRUE.equals(request.getQueueAnnounceEnabled()) && request.getQueueAnnounceMediaId() == null) {
            throw new ServiceException("启用排队提醒时必须选择提醒语音");
        }
    }

    private void requireTarget(String label, String action, String target) {
        if (Set.of("VOICEMAIL", "IVR", "EXTENSION", "QUEUE").contains(action)
            && org.apache.commons.lang3.StringUtils.isBlank(target)) {
            throw new ServiceException(label + "选择转接类动作时必须配置目标");
        }
    }

    private void validatePublishedMedia(Long mediaId, String category, String message) {
        MediaAsset media = mediaAssetMapper.selectById(mediaId);
        if (media == null || !Boolean.TRUE.equals(media.getEnabled())
            || !category.equals(media.getCategory())
            || !"PUBLISHED".equals(media.getPublishStatus())) {
            throw new ServiceException(message);
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
        queue.setCallerNumberId(request.getCallerNumberId());
        queue.setMaskCallerNumber(request.getMaskCallerNumber());
        queue.setManualAnswer(request.getManualAnswer());
        queue.setBusyTransferMobile(request.getBusyTransferMobile());
        queue.setBusyTransferNumber(request.getBusyTransferNumber());
        queue.setForceWaitSeconds(request.getForceWaitSeconds());
        queue.setForceWaitMediaId(request.getForceWaitMediaId());
        queue.setAnswerAction(request.getAnswerAction());
        queue.setAnswerMediaId(request.getAnswerMediaId());
        queue.setHangupKeyAction(request.getHangupKeyAction());
        queue.setSatisfactionEnabled(request.getSatisfactionEnabled());
        queue.setSatisfactionMediaId(request.getSatisfactionMediaId());
        queue.setSatisfactionTimeoutSeconds(request.getSatisfactionTimeoutSeconds());
        queue.setTimeoutAction(request.getTimeoutAction());
        queue.setTimeoutTarget(request.getTimeoutTarget());
        queue.setNoAgentAction(request.getNoAgentAction());
        queue.setNoAgentTarget(request.getNoAgentTarget());
        queue.setNoAgentWaitSeconds(request.getNoAgentWaitSeconds());
        queue.setAgentNoAnswerAction(request.getAgentNoAnswerAction());
        queue.setAgentTimeoutTransferMobile(request.getAgentTimeoutTransferMobile());
        queue.setAgentTimeoutTransferNumber(request.getAgentTimeoutTransferNumber());
        queue.setStickyAgentEnabled(request.getStickyAgentEnabled());
        queue.setQueueAnnounceEnabled(request.getQueueAnnounceEnabled());
        queue.setQueueAnnounceInterval(request.getQueueAnnounceInterval());
        queue.setQueueAnnounceMediaId(request.getQueueAnnounceMediaId());
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
        response.setCallerNumberId(queue.getCallerNumberId());
        response.setMaskCallerNumber(queue.getMaskCallerNumber());
        response.setManualAnswer(queue.getManualAnswer());
        response.setBusyTransferMobile(queue.getBusyTransferMobile());
        response.setBusyTransferNumber(queue.getBusyTransferNumber());
        response.setForceWaitSeconds(queue.getForceWaitSeconds());
        response.setForceWaitMediaId(queue.getForceWaitMediaId());
        response.setAnswerAction(queue.getAnswerAction());
        response.setAnswerMediaId(queue.getAnswerMediaId());
        response.setHangupKeyAction(queue.getHangupKeyAction());
        response.setSatisfactionEnabled(Boolean.TRUE.equals(queue.getSatisfactionEnabled()));
        response.setSatisfactionMediaId(queue.getSatisfactionMediaId());
        response.setSatisfactionTimeoutSeconds(queue.getSatisfactionTimeoutSeconds() == null ? 8 : queue.getSatisfactionTimeoutSeconds());
        response.setTimeoutAction(queue.getTimeoutAction());
        response.setTimeoutTarget(queue.getTimeoutTarget());
        response.setNoAgentAction(queue.getNoAgentAction());
        response.setNoAgentTarget(queue.getNoAgentTarget());
        response.setNoAgentWaitSeconds(queue.getNoAgentWaitSeconds());
        response.setAgentNoAnswerAction(queue.getAgentNoAnswerAction());
        response.setAgentTimeoutTransferMobile(queue.getAgentTimeoutTransferMobile());
        response.setAgentTimeoutTransferNumber(queue.getAgentTimeoutTransferNumber());
        response.setStickyAgentEnabled(queue.getStickyAgentEnabled());
        response.setQueueAnnounceEnabled(queue.getQueueAnnounceEnabled());
        response.setQueueAnnounceInterval(queue.getQueueAnnounceInterval());
        response.setQueueAnnounceMediaId(queue.getQueueAnnounceMediaId());
        response.setMaxWaitSeconds(queue.getMaxWaitSeconds());
        response.setRingTimeoutSeconds(queue.getRingTimeoutSeconds());
        response.setMaxNoAnswer(queue.getMaxNoAnswer());
        response.setWrapUpSeconds(queue.getWrapUpSeconds());
        response.setSyncStatus(queue.getSyncStatus());
        response.setLastSyncedAt(queue.getLastSyncedAt());
        response.setSyncError("FAILED".equals(queue.getSyncStatus()) || "PARTIAL".equals(queue.getSyncStatus()) ? queue.getSyncError() : null);
        response.setEnabled(queue.getEnabled());
        response.setRemark(queue.getRemark());
        response.setVersion(queue.getVersion());
        response.setCreateTime(queue.getCreateTime());
        return response;
    }

    private void fillDialplanOptions(CallQueueDialplanResponse response, CallQueue queue, Long nodeId) {
        response.setMaskCallerNumber(Boolean.TRUE.equals(queue.getMaskCallerNumber()));
        response.setManualAnswer(Boolean.TRUE.equals(queue.getManualAnswer()));
        response.setForceWaitSeconds(queue.getForceWaitSeconds() == null ? 0 : queue.getForceWaitSeconds());
        response.setForceWaitMediaPath(mediaPath(queue.getForceWaitMediaId(), nodeId, "入队前提示音"));
        response.setTimeoutAction(blankDefault(queue.getTimeoutAction(), "HANGUP"));
        response.setTimeoutTarget(queue.getTimeoutTarget());
        response.setNoAgentAction(blankDefault(queue.getNoAgentAction(), "WAIT"));
        response.setNoAgentTarget(queue.getNoAgentTarget());
        response.setNoAgentWaitSeconds(queue.getNoAgentWaitSeconds() == null ? 0 : queue.getNoAgentWaitSeconds());
        String satisfactionMediaPath = mediaPath(queue.getSatisfactionMediaId(), nodeId, "满意度评价提示音");
        response.setSatisfactionEnabled(Boolean.TRUE.equals(queue.getSatisfactionEnabled())
            && org.apache.commons.lang3.StringUtils.isNotBlank(satisfactionMediaPath));
        response.setSatisfactionMediaPath(satisfactionMediaPath);
        response.setSatisfactionTimeoutSeconds(queue.getSatisfactionTimeoutSeconds() == null ? 8 : queue.getSatisfactionTimeoutSeconds());
        response.setTimeoutTargetQueueCode(resolveTargetQueueCode(queue.getTimeoutAction(), queue.getTimeoutTarget(), nodeId));
        response.setNoAgentTargetQueueCode(resolveTargetQueueCode(queue.getNoAgentAction(), queue.getNoAgentTarget(), nodeId));
    }

    private String resolveTargetQueueCode(String action, String target, Long nodeId) {
        if (!"QUEUE".equals(action) || org.apache.commons.lang3.StringUtils.isBlank(target)) {
            return null;
        }
        try {
            CallQueue targetQueue = queueMapper.selectById(Long.valueOf(target));
            if (targetQueue == null || !Boolean.TRUE.equals(targetQueue.getEnabled())) {
                throw new ServiceException("目标队列不存在或已停用");
            }
            if (!nodeIds(targetQueue.getNodeGroupId()).contains(nodeId)) {
                throw new ServiceException("目标队列未覆盖当前 FreeSWITCH 节点");
            }
            return targetQueue.getQueueCode();
        } catch (NumberFormatException exception) {
            throw new ServiceException("目标队列格式不正确");
        }
    }

    private String blankDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
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
                    sipAccount.getAuthUsername(), sipAccount.getDomain(), member.getSkillLevel(), member.getPriority(), queue.getRingTimeoutSeconds(),
                    queue.getMaxNoAnswer(), queue.getWrapUpSeconds(), presenceStatus(queue.getTenantId(), agent.getId()),
                    answerActionMediaPath(queue, agent.getId(), nodeId)));
            }
            if (agents.isEmpty()) {
                throw new ServiceException("节点 " + nodeId + " 没有绑定可用 SIP 分机的技能组坐席");
            }
            configs.add(new QueueNodeRuntimeConfig(nodeId, queue.getQueueCode(), queue.getStrategy(),
                waitMediaPath(queue, nodeId),
                Boolean.TRUE.equals(queue.getQueueAnnounceEnabled()),
                queue.getQueueAnnounceInterval(),
                mediaPath(queue.getQueueAnnounceMediaId(), nodeId, "排队提醒音"),
                blankDefault(queue.getAnswerAction(), "NONE"),
                blankDefault(queue.getAgentNoAnswerAction(), "NEXT_AGENT"),
                queue.getMaxWaitSeconds(), agents));
        }
        if (configs.isEmpty()) {
            throw new ServiceException("队列关联的 FreeSWITCH 节点组没有成员节点");
        }
        return configs;
    }

    private QueueNodeRuntimeConfig runtimeAgentStatusConfig(CallQueue queue, Long nodeId) {
        List<SkillGroupMember> members = skillGroupMemberMapper.selectList(new LambdaQueryWrapper<SkillGroupMember>()
            .eq(SkillGroupMember::getSkillGroupId, queue.getSkillGroupId())
            .orderByAsc(SkillGroupMember::getPriority));
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
                sipAccount.getAuthUsername(), sipAccount.getDomain(), member.getSkillLevel(), member.getPriority(), queue.getRingTimeoutSeconds(),
                queue.getMaxNoAnswer(), queue.getWrapUpSeconds(), presenceStatus(queue.getTenantId(), agent.getId()),
                answerActionMediaPath(queue, agent.getId(), nodeId)));
        }
        if (agents.isEmpty()) {
            throw new ServiceException("No available SIP extension agent is bound to node " + nodeId);
        }
        return new QueueNodeRuntimeConfig(nodeId, queue.getQueueCode(), queue.getStrategy(),
            null, false, null, null, blankDefault(queue.getAnswerAction(), "NONE"), "NEXT_AGENT", queue.getMaxWaitSeconds(), agents);
    }

    private String answerActionMediaPath(CallQueue queue, Long agentId, Long nodeId) {
        String action = blankDefault(queue.getAnswerAction(), "NONE");
        if ("PLAY_MEDIA".equals(action)) {
            return mediaPath(queue.getAnswerMediaId(), nodeId, "queue answer prompt");
        }
        if ("PLAY_AGENT_NUMBER".equals(action) && agentId != null) {
            return generatedMediaQueryService.findSyncedPath(
                AiSpeechApplicationServiceImpl.BUSINESS_AGENT_NUMBER_PROMPT,
                agentId,
                nodeId
            );
        }
        return null;
    }

    private List<Long> nodeIds(Long nodeGroupId) {
        return nodeGroupMemberMapper.selectList(new LambdaQueryWrapper<FreeSwitchNodeGroupMember>()
                .eq(FreeSwitchNodeGroupMember::getGroupId, nodeGroupId))
            .stream().map(FreeSwitchNodeGroupMember::getNodeId).distinct().toList();
    }

    private String waitMediaPath(CallQueue queue, Long nodeId) {
        return mediaPath(queue.getWaitMediaId(), nodeId, "队列等待音");
    }

    private String mediaPath(Long mediaId, Long nodeId, String label) {
        if (mediaId == null) return null;
        MediaAsset media = mediaAssetMapper.selectById(mediaId);
        if (media == null || media.getLatestVersionId() == null) {
            throw new ServiceException(label + "不存在或没有可用版本");
        }
        List<Long> activePublicationIds = mediaPublicationMapper.selectList(new LambdaQueryWrapper<MediaPublication>()
                .eq(MediaPublication::getMediaId, mediaId)
                .eq(MediaPublication::getVersionId, media.getLatestVersionId())
                .in(MediaPublication::getStatus, List.of("PUBLISHING", "PARTIAL", "PUBLISHED")))
            .stream().map(MediaPublication::getId).toList();
        if (activePublicationIds.isEmpty()) {
            throw new ServiceException(label + "当前版本没有有效发布记录");
        }
        MediaNodeSync sync = mediaNodeSyncMapper.selectOne(new LambdaQueryWrapper<MediaNodeSync>()
            .eq(MediaNodeSync::getMediaId, mediaId)
            .in(MediaNodeSync::getPublicationId, activePublicationIds)
            .eq(MediaNodeSync::getNodeId, nodeId)
            .eq(MediaNodeSync::getStatus, "SUCCESS")
            .orderByDesc(MediaNodeSync::getSyncedAt)
            .last("limit 1"));
        if (sync == null) {
            throw new ServiceException(label + "尚未同步到节点 " + nodeId);
        }
        return sync.getTargetPath();
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 1000) return value;
        return value.substring(0, 1000);
    }

    private AgentPresenceStatus presenceStatus(String tenantId, Long agentId) {
        String resolvedTenantId = org.apache.commons.lang3.StringUtils.defaultIfBlank(tenantId, LoginHelper.getTenantId());
        AgentPresence presence = RedisUtils.getCacheObject("callnexus:agent:presence:" + resolvedTenantId + ":" + agentId);
        return presence == null ? AgentPresenceStatus.OFFLINE : presence.getStatus();
    }

    /**
     * 记忆坐席命中：把 {@link StickyAgentRegistry} 返回的桥接目标写到 dialplan 响应上，
     * 供 {@code QueueDialplanRouteHandler} 渲染直拨分机的 XML，绕过 mod_callcenter。
     */
    private void fillStickyAgentTarget(CallQueueDialplanResponse response, CallQueue queue,
                                       String tenantId, String callerNumber, Long nodeId) {
        boolean enabled = Boolean.TRUE.equals(queue.getStickyAgentEnabled());
        response.setStickyAgentEnabled(enabled);
        if (!enabled || org.apache.commons.lang3.StringUtils.isBlank(callerNumber)) {
            return;
        }
        String target = stickyAgentRegistry.findStickyAgentTarget(tenantId, queue.getId(), callerNumber, nodeId);
        response.setStickyAgentTarget(target);
    }

    /**
     * 转手机能力：把队列上配置的遇忙/超时手机号回填到 dialplan 响应上。
     *
     * <p>默认外呼网关编码由 {@code QueueDialplanRouteHandler} 在 dialplan 渲染前补全，
     * 避免 agent 模块依赖 {@code PhoneNumberQueryService}（其实现链路反向依赖到 agent）。
     */
    private void fillMobileTransferOptions(CallQueueDialplanResponse response, CallQueue queue,
                                            String tenantId, Long nodeId) {
        response.setBusyTransferMobile(Boolean.TRUE.equals(queue.getBusyTransferMobile()));
        response.setBusyTransferNumber(queue.getBusyTransferNumber());
        response.setAgentTimeoutTransferMobile(Boolean.TRUE.equals(queue.getAgentTimeoutTransferMobile()));
        response.setAgentTimeoutTransferNumber(queue.getAgentTimeoutTransferNumber());
    }
}
