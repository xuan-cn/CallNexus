package org.dromara.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.agent.domain.CallQueue;
import org.dromara.agent.mapper.CallQueueMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.freeswitch.callcenter.FreeSwitchCallCenterConfigurationContext;
import org.dromara.resource.freeswitch.callcenter.FreeSwitchCallCenterConfigurationContributor;
import org.dromara.resource.freeswitch.callcenter.FreeSwitchCallCenterConfigurationDocument;
import org.dromara.resource.freeswitch.xml.FreeSwitchXmlRenderer;
import org.dromara.resource.media.domain.MediaAsset;
import org.dromara.resource.media.domain.MediaNodeSync;
import org.dromara.resource.media.domain.MediaPublication;
import org.dromara.resource.media.mapper.MediaAssetMapper;
import org.dromara.resource.media.mapper.MediaNodeSyncMapper;
import org.dromara.resource.media.mapper.MediaPublicationMapper;
import org.dromara.resource.node.group.domain.FreeSwitchNodeGroupMember;
import org.dromara.resource.node.group.mapper.FreeSwitchNodeGroupMemberMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 将 CallNexus 呼叫队列贡献到 FreeSWITCH mod_callcenter 动态配置。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CallQueueConfigurationContributor implements FreeSwitchCallCenterConfigurationContributor {
    private static final String CONTRIBUTOR_CODE = "CALL_QUEUE";

    private final CallQueueMapper queueMapper;
    private final FreeSwitchNodeGroupMemberMapper nodeGroupMemberMapper;
    private final MediaAssetMapper mediaAssetMapper;
    private final MediaNodeSyncMapper mediaNodeSyncMapper;
    private final MediaPublicationMapper mediaPublicationMapper;

    @Override
    public String contributorCode() {
        return CONTRIBUTOR_CODE;
    }

    @Override
    public void contribute(FreeSwitchCallCenterConfigurationContext context, FreeSwitchCallCenterConfigurationDocument document) {
        TenantHelper.dynamic(context.tenantId(), () -> contributeForTenant(context.nodeId(), document));
    }

    private void contributeForTenant(Long nodeId, FreeSwitchCallCenterConfigurationDocument document) {
        List<CallQueue> queues = queueMapper.selectList(new LambdaQueryWrapper<CallQueue>()
                .eq(CallQueue::getEnabled, true)
                .orderByAsc(CallQueue::getQueueCode))
            .stream()
            .filter(queue -> nodeIds(queue.getNodeGroupId()).contains(nodeId))
            .toList();
        int rendered = 0;
        for (CallQueue queue : queues) {
            String queueXml;
            try {
                queueXml = renderQueue(queue, nodeId);
            } catch (ServiceException exception) {
                log.warn("跳过无法下发的 FreeSWITCH 呼叫队列，nodeId={}，queueCode={}，reason={}",
                    nodeId, queue.getQueueCode(), exception.getMessage());
                continue;
            }
            document.addQueue(queue.getQueueCode() + "@default", queueXml);
            rendered++;
        }
        log.info("已贡献 FreeSWITCH 呼叫队列配置，nodeId={}，queueCount={}", nodeId, rendered);
    }

    private String renderQueue(CallQueue queue, Long nodeId) {
        String waitMediaPath = waitMediaPath(queue, nodeId);
        return "  <queue name=\"" + FreeSwitchXmlRenderer.escape(queue.getQueueCode()) + "@default\">\n"
            + "    <param name=\"strategy\" value=\"" + strategy(queue.getStrategy()) + "\"/>\n"
            + "    <param name=\"moh-sound\" value=\""
            + FreeSwitchXmlRenderer.escape(waitMediaPath == null ? "local_stream://moh" : waitMediaPath) + "\"/>\n"
            + "    <param name=\"time-base-score\" value=\"system\"/>\n"
            + "    <param name=\"max-wait-time\" value=\"" + queue.getMaxWaitSeconds() + "\"/>\n"
            + "    <param name=\"max-wait-time-with-no-agent\" value=\"0\"/>\n"
            + "    <param name=\"max-wait-time-with-no-agent-time-reached\" value=\"5\"/>\n"
            + announceParams(queue, nodeId)
            + "    <param name=\"agent-no-answer-status\" value=\"" + agentNoAnswerStatus(queue.getAgentNoAnswerAction()) + "\"/>\n"
            + "    <param name=\"tier-rules-apply\" value=\"false\"/>\n"
            + "    <param name=\"tier-rule-wait-second\" value=\"300\"/>\n"
            + "    <param name=\"tier-rule-wait-multiply-level\" value=\"true\"/>\n"
            + "    <param name=\"tier-rule-no-agent-no-wait\" value=\"false\"/>\n"
            + "    <param name=\"discard-abandoned-after\" value=\"60\"/>\n"
            + "    <param name=\"abandoned-resume-allowed\" value=\"false\"/>\n"
            + "  </queue>";
    }

    private String announceParams(CallQueue queue, Long nodeId) {
        if (!Boolean.TRUE.equals(queue.getQueueAnnounceEnabled())) {
            return "";
        }
        String mediaPath = mediaPath(queue.getQueueAnnounceMediaId(), nodeId, "排队提醒音");
        Integer interval = queue.getQueueAnnounceInterval() == null ? 30 : queue.getQueueAnnounceInterval();
        return "    <param name=\"announce-sound\" value=\"" + FreeSwitchXmlRenderer.escape(mediaPath) + "\"/>\n"
            + "    <param name=\"announce-frequency\" value=\"" + interval + "\"/>\n";
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
        if (mediaId == null) {
            return null;
        }
        MediaAsset media = mediaAssetMapper.selectById(mediaId);
        if (media == null || media.getLatestVersionId() == null) {
            throw new ServiceException(label + "不存在或没有可用版本");
        }
        List<Long> publicationIds = mediaPublicationMapper.selectList(new LambdaQueryWrapper<MediaPublication>()
                .eq(MediaPublication::getMediaId, mediaId)
                .eq(MediaPublication::getVersionId, media.getLatestVersionId())
                .in(MediaPublication::getStatus, List.of("PUBLISHING", "PARTIAL", "PUBLISHED")))
            .stream().map(MediaPublication::getId).toList();
        if (publicationIds.isEmpty()) {
            throw new ServiceException(label + "当前版本没有有效发布记录");
        }
        MediaNodeSync sync = mediaNodeSyncMapper.selectOne(new LambdaQueryWrapper<MediaNodeSync>()
            .eq(MediaNodeSync::getMediaId, mediaId)
            .in(MediaNodeSync::getPublicationId, publicationIds)
            .eq(MediaNodeSync::getNodeId, nodeId)
            .eq(MediaNodeSync::getStatus, "SUCCESS")
            .orderByDesc(MediaNodeSync::getSyncedAt)
            .last("limit 1"));
        if (sync == null) {
            throw new ServiceException(label + "尚未同步到节点 " + nodeId);
        }
        return sync.getTargetPath();
    }

    private String agentNoAnswerStatus(String action) {
        return "BREAK_AGENT".equals(action) ? "On Break" : "Available";
    }

    private String strategy(String strategy) {
        return switch (strategy) {
            case "LONGEST_IDLE_AGENT" -> "longest-idle-agent";
            case "ROUND_ROBIN" -> "round-robin";
            case "TOP_DOWN" -> "top-down";
            case "RING_ALL" -> "ring-all";
            default -> throw new ServiceException("不支持的 FreeSWITCH 队列分配策略");
        };
    }
}
