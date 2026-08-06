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
import java.util.Set;

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
    public void syncQueueAgentStatuses(List<QueueNodeRuntimeConfig> nodes) {
        for (QueueNodeRuntimeConfig node : nodes) {
            EslEndpoint endpoint = endpoint(node.nodeId());
            Set<String> registeredUsers = registeredUsers(endpoint);
            for (QueueAgentRuntimeConfig agent : node.agents()) {
                syncAgentStatus(endpoint, node.nodeId(), agent, registeredUsers);
            }
        }
    }

    @Override
    public void syncAgentStatus(AgentQueueRuntimeStatus status) {
        String agent = agentName(status.extension(), status.sipDomain());
        EslEndpoint endpoint = endpoint(status.nodeId());
        Set<String> registeredUsers = registeredUsers(endpoint);
        AgentPresenceStatus effectiveStatus = effectivePresenceStatus(status.presenceStatus(), status.extension(), status.authUsername(), registeredUsers);
        execute(endpoint, "api callcenter_config agent set status " + agent + " '" + mapStatus(effectiveStatus) + "'");
        log.info("已同步 FreeSWITCH 队列坐席状态，nodeId={}，agent={}，status={}，effectiveStatus={}，registered={}",
            status.nodeId(), agent, status.presenceStatus(), effectiveStatus, isRegistered(status.extension(), status.authUsername(), registeredUsers));
    }

    private void syncAgentStatus(EslEndpoint endpoint, Long nodeId, QueueAgentRuntimeConfig config, Set<String> registeredUsers) {
        String agent = agentName(config.extension(), config.sipDomain());
        AgentPresenceStatus effectiveStatus = effectivePresenceStatus(
            config.presenceStatus(), config.extension(), config.authUsername(), registeredUsers);
        execute(endpoint, "api callcenter_config agent set status " + agent + " '" + mapStatus(effectiveStatus) + "'");
        log.info("Refreshed FreeSWITCH queue agent status before queue entry, nodeId={}, agent={}, status={}, effectiveStatus={}, registered={}",
            nodeId, agent, config.presenceStatus(), effectiveStatus,
            isRegistered(config.extension(), config.authUsername(), registeredUsers));
    }

    private void syncNode(QueueNodeRuntimeConfig config) {
        EslEndpoint endpoint = endpoint(config.nodeId());
        String queue = queueName(config.queueCode());
        Set<String> registeredUsers = registeredUsers(endpoint);
        executeIgnoringError(endpoint, "api callcenter_config queue unload " + queue);
        execute(endpoint, "api callcenter_config queue load " + queue);
        clearStaleTiers(endpoint, queue, config.agents());
        for (QueueAgentRuntimeConfig agent : config.agents()) {
            syncAgent(endpoint, queue, agent, registeredUsers);
        }
        log.info("FreeSWITCH 队列同步完成，nodeId={}，queueCode={}，agentCount={}，queueAnnounceEnabled={}，agentNoAnswerAction={}",
            config.nodeId(), config.queueCode(), config.agents().size(), config.queueAnnounceEnabled(), config.agentNoAnswerAction());
    }

    private void syncAgent(EslEndpoint endpoint, String queue, QueueAgentRuntimeConfig config, Set<String> registeredUsers) {
        String agent = agentName(config.extension(), config.sipDomain());
        AgentPresenceStatus effectiveStatus = effectivePresenceStatus(
            config.presenceStatus(), config.extension(), config.authUsername(), registeredUsers);
        executeIgnoringError(endpoint, "api callcenter_config agent add " + agent + " callback");
        execute(endpoint, "api callcenter_config agent set status " + agent + " '" + mapStatus(effectiveStatus) + "'");
        execute(endpoint, "api callcenter_config agent set contact " + agent + " " + contactDialString(config));
        executeIgnoringError(endpoint, "api callcenter_config tier del " + queue + " " + agent);
        execute(endpoint, "api callcenter_config tier add " + queue + " " + agent + " " + config.level() + " " + config.position());
        log.info("已同步 FreeSWITCH 队列坐席配置，agent={}，ringTimeoutSeconds={}，maxNoAnswer={}，wrapUpSeconds={}，status={}，effectiveStatus={}，registered={}，answerActionMediaPath={}，"
                + "其中最大未接次数和话后整理由 CallNexus 业务状态控制",
            agent, config.ringTimeoutSeconds(), config.maxNoAnswer(), config.wrapUpSeconds(), config.presenceStatus(),
            effectiveStatus, isRegistered(config.extension(), config.authUsername(), registeredUsers), config.answerActionMediaPath());
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

    private String executeForResult(EslEndpoint endpoint, String command) {
        return commandGateway.executeApiCommandForResult(endpoint, command);
    }

    private void clearStaleTiers(EslEndpoint endpoint, String queue, List<QueueAgentRuntimeConfig> agents) {
        Set<String> desiredAgents = new java.util.LinkedHashSet<>();
        for (QueueAgentRuntimeConfig agentConfig : agents) {
            desiredAgents.add(agentName(agentConfig.extension(), agentConfig.sipDomain()));
        }
        String result;
        try {
            result = executeForResult(endpoint, "api callcenter_config tier list");
        } catch (Exception exception) {
            log.warn("Failed to list FreeSWITCH queue tiers before sync, queue={}, error={}", queue, exception.getMessage());
            return;
        }
        Set<String> existingAgents = parseTierAgents(result, queue);
        for (String existingAgent : existingAgents) {
            if (!desiredAgents.contains(existingAgent)) {
                executeIgnoringError(endpoint, "api callcenter_config tier del " + queue + " " + existingAgent);
                log.info("Removed stale FreeSWITCH queue tier, queue={}, agent={}", queue, existingAgent);
            }
        }
    }

    private Set<String> parseTierAgents(String result, String queue) {
        Set<String> agents = new java.util.LinkedHashSet<>();
        if (result == null || result.isBlank()) {
            return agents;
        }
        for (String line : result.split("\\R")) {
            if (!line.contains(queue)) {
                continue;
            }
            String[] parts = line.trim().split("[|,\\s]+");
            for (String part : parts) {
                if (!part.equals(queue) && part.contains("@") && part.matches("[A-Za-z0-9_-]{1,64}@[A-Za-z0-9.-]{1,255}")) {
                    agents.add(part);
                    break;
                }
            }
        }
        return agents;
    }

    private String contactDialString(QueueAgentRuntimeConfig config) {
        if (config.extension() == null || config.extension().isBlank()) {
            throw new ServiceException("队列坐席分机号不能为空");
        }
        StringBuilder variables = new StringBuilder();
        variables.append("leg_timeout=").append(positiveSeconds(config.ringTimeoutSeconds(), "agent ring timeout"));
        return "[" + variables + "]user/" + safeCode(config.extension()) + "@" + safeDomain(config.sipDomain());
    }

    private String mapStatus(AgentPresenceStatus status) {
        return switch (status) {
            case IDLE -> "Available";
            case BUSY, AFTER_CALL -> "On Break";
            case OFFLINE -> "Logged Out";
        };
    }

    private AgentPresenceStatus effectivePresenceStatus(AgentPresenceStatus status, String extension, String authUsername, Set<String> registeredUsers) {
        if (status == AgentPresenceStatus.IDLE && !isRegistered(extension, authUsername, registeredUsers)) {
            return AgentPresenceStatus.OFFLINE;
        }
        return status;
    }

    private Set<String> registeredUsers(EslEndpoint endpoint) {
        try {
            return commandGateway.listRegisteredExtensions(endpoint);
        } catch (Exception exception) {
            log.warn("查询 FreeSWITCH SIP 注册列表失败，保留业务坐席状态同步，node={}，error={}",
                endpoint.sipDomain(), exception.getMessage());
            return null;
        }
    }

    private boolean isRegistered(String extension, String authUsername, Set<String> registeredUsers) {
        if (registeredUsers == null) {
            return true;
        }
        return registeredUsers.contains(extension) || registeredUsers.contains(authUsername);
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
