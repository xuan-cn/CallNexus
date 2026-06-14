package org.dromara.esl.infrastructure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.agent.domain.AgentPresenceStatus;
import org.dromara.agent.runtime.AgentQueueRuntimeStatus;
import org.dromara.agent.runtime.QueueAgentRuntimeConfig;
import org.dromara.agent.runtime.QueueNodeRuntimeConfig;
import org.dromara.agent.runtime.QueueRuntimeSyncResult;
import org.dromara.agent.service.CallQueueRuntimeSyncService;
import org.dromara.call.domain.EslEndpoint;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.resource.node.domain.response.FreeSwitchNodeConnectionResponse;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FreeSwitchCallQueueRuntimeSyncServiceImpl implements CallQueueRuntimeSyncService {
    private final FreeSwitchNodeQueryService nodeQueryService;
    private final FreeSwitchEslCommandGateway commandGateway;

    @Override
    public QueueRuntimeSyncResult syncQueue(List<QueueNodeRuntimeConfig> nodes) {
        List<String> errors = new ArrayList<>();
        int success = 0;
        for (QueueNodeRuntimeConfig node : nodes) {
            try {
                syncNode(node);
                success++;
            } catch (Exception exception) {
                errors.add("节点 " + node.nodeId() + "：" + exception.getMessage());
                log.error("FreeSWITCH 队列同步失败，nodeId={}，queueCode={}", node.nodeId(), node.queueCode(), exception);
            }
        }
        return new QueueRuntimeSyncResult(success, errors.size(), errors);
    }

    @Override
    public QueueRuntimeSyncResult removeQueue(List<Long> nodeIds, String queueCode) {
        List<String> errors = new ArrayList<>();
        int success = 0;
        for (Long nodeId : nodeIds) {
            try {
                executeIgnoringError(endpoint(nodeId), "api callcenter_config queue unload " + queueName(queueCode));
                success++;
            } catch (Exception exception) {
                errors.add("节点 " + nodeId + "：" + exception.getMessage());
            }
        }
        return new QueueRuntimeSyncResult(success, errors.size(), errors);
    }

    @Override
    public void syncAgentStatus(AgentQueueRuntimeStatus status) {
        String agent = agentName(status.extension(), status.sipDomain());
        execute(endpoint(status.nodeId()), "api callcenter_config agent set status " + agent + " '" + mapStatus(status.presenceStatus()) + "'");
        log.info("已同步 FreeSWITCH 队列坐席状态，nodeId={}，agent={}，status={}", status.nodeId(), agent, status.presenceStatus());
    }

    private void syncNode(QueueNodeRuntimeConfig config) {
        EslEndpoint endpoint = endpoint(config.nodeId());
        String queue = queueName(config.queueCode());
        executeIgnoringError(endpoint, "api callcenter_config queue unload " + queue);
        execute(endpoint, "api callcenter_config queue load " + queue);
        for (QueueAgentRuntimeConfig agent : config.agents()) {
            syncAgent(endpoint, queue, agent);
        }
        log.info("FreeSWITCH 队列同步完成，nodeId={}，queueCode={}，agentCount={}", config.nodeId(), config.queueCode(), config.agents().size());
    }

    private void syncAgent(EslEndpoint endpoint, String queue, QueueAgentRuntimeConfig config) {
        String agent = agentName(config.extension(), config.sipDomain());
        executeIgnoringError(endpoint, "api callcenter_config agent add " + agent + " callback");
        execute(endpoint, "api callcenter_config agent set contact " + agent
            + " [leg_timeout=" + positiveSeconds(config.ringTimeoutSeconds(), "坐席振铃超时") + "]user/" + agent);
        execute(endpoint, "api callcenter_config agent set status " + agent + " '" + mapStatus(config.presenceStatus()) + "'");
        executeIgnoringError(endpoint, "api callcenter_config tier del " + queue + " " + agent);
        execute(endpoint, "api callcenter_config tier add " + queue + " " + agent + " " + config.level() + " " + config.position());
        log.info("已同步 FreeSWITCH 队列坐席配置，agent={}，ringTimeoutSeconds={}，maxNoAnswer={}，wrapUpSeconds={}，"
                + "其中最大未接次数和话后整理由 CallNexus 业务状态控制",
            agent, config.ringTimeoutSeconds(), config.maxNoAnswer(), config.wrapUpSeconds());
    }

    private EslEndpoint endpoint(Long nodeId) {
        FreeSwitchNodeConnectionResponse node = nodeQueryService.getEnabledConnection(nodeId);
        return new EslEndpoint(node.getEslHost(), node.getEslPort(), node.getEslPassword(), node.getSipDomain());
    }

    private void execute(EslEndpoint endpoint, String command) {
        commandGateway.executeApiCommand(endpoint, command);
    }

    private void executeIgnoringError(EslEndpoint endpoint, String command) {
        commandGateway.executeApiCommandIgnoringApplicationError(endpoint, command);
    }

    private String mapStatus(AgentPresenceStatus status) {
        return switch (status) {
            case IDLE, BUSY -> "Available";
            case AFTER_CALL -> "On Break";
            case OFFLINE -> "Logged Out";
        };
    }

    private String queueName(String queueCode) {
        return safeCode(queueCode) + "@default";
    }

    private String agentName(String extension, String domain) {
        return safeCode(extension) + "@" + safeDomain(domain);
    }

    private String safeCode(String value) {
        if (value == null || !value.matches("^[A-Za-z0-9_-]{1,64}$")) {
            throw new ServiceException("FreeSWITCH 队列或坐席编码不合法");
        }
        return value;
    }

    private String safeDomain(String value) {
        if (value == null || !value.matches("^[A-Za-z0-9.-]{1,255}$")) {
            throw new ServiceException("FreeSWITCH SIP 域不合法");
        }
        return value;
    }

    private int positiveSeconds(Integer value, String fieldName) {
        if (value == null || value <= 0 || value > 86400) {
            throw new ServiceException(fieldName + "不合法");
        }
        return value;
    }

}
