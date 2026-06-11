package org.dromara.esl.infrastructure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.call.domain.EslEndpoint;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.resource.gateway.service.FreeSwitchGatewayRuntimeSyncService;
import org.dromara.resource.node.domain.response.FreeSwitchNodeConnectionResponse;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class FreeSwitchGatewayRuntimeSyncServiceImpl implements FreeSwitchGatewayRuntimeSyncService {
    private static final String PROFILE = "external";
    private static final long GATEWAY_REMOVAL_WAIT_MILLIS = 1500L;

    private final FreeSwitchNodeQueryService nodeQueryService;
    private final FreeSwitchEslCommandGateway commandGateway;

    @Override
    public void addGateway(Long nodeId, String gatewayCode) {
        EslEndpoint endpoint = endpoint(nodeId);
        log.info("开始同步 FreeSWITCH 网关运行态，操作=新增，nodeId={}，profile={}，gatewayCode={}", nodeId, PROFILE, gatewayCode);
        execute(endpoint, "api reloadxml");
        execute(endpoint, "api sofia profile " + PROFILE + " rescan");
        log.info("FreeSWITCH 网关运行态同步完成，操作=新增，nodeId={}，profile={}，gatewayCode={}", nodeId, PROFILE, gatewayCode);
    }

    @Override
    public void updateGateway(Long nodeId, String gatewayCode) {
        EslEndpoint endpoint = endpoint(nodeId);
        String safeGatewayCode = safeGatewayCode(gatewayCode);
        log.info("开始同步 FreeSWITCH 网关运行态，操作=修改，nodeId={}，profile={}，gatewayCode={}", nodeId, PROFILE, safeGatewayCode);
        execute(endpoint, "api sofia profile " + PROFILE + " killgw " + safeGatewayCode);
        waitForGatewayRemoval(safeGatewayCode);
        execute(endpoint, "api reloadxml");
        execute(endpoint, "api sofia profile " + PROFILE + " rescan");
        log.info("FreeSWITCH 网关运行态同步完成，操作=修改，nodeId={}，profile={}，gatewayCode={}", nodeId, PROFILE, safeGatewayCode);
    }

    @Override
    public void removeGateway(Long nodeId, String gatewayCode) {
        EslEndpoint endpoint = endpoint(nodeId);
        log.info("开始同步 FreeSWITCH 网关运行态，操作=删除，nodeId={}，profile={}，gatewayCode={}", nodeId, PROFILE, gatewayCode);
        execute(endpoint, "api sofia profile " + PROFILE + " killgw " + safeGatewayCode(gatewayCode));
        log.info("FreeSWITCH 网关运行态同步完成，操作=删除，nodeId={}，profile={}，gatewayCode={}", nodeId, PROFILE, gatewayCode);
    }

    private void waitForGatewayRemoval(String gatewayCode) {
        try {
            Thread.sleep(GATEWAY_REMOVAL_WAIT_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("等待 FreeSWITCH 删除旧网关时线程被中断，gatewayCode={}", gatewayCode);
            throw new ServiceException("FREESWITCH_GATEWAY_SYNC_INTERRUPTED");
        }
    }

    private EslEndpoint endpoint(Long nodeId) {
        FreeSwitchNodeConnectionResponse node = nodeQueryService.getEnabledConnection(nodeId);
        return new EslEndpoint(node.getEslHost(), node.getEslPort(), node.getEslPassword(), node.getSipDomain());
    }

    private void execute(EslEndpoint endpoint, String command) {
        commandGateway.executeApiCommand(endpoint, command);
    }

    private String safeGatewayCode(String gatewayCode) {
        if (gatewayCode == null || !gatewayCode.matches("^[A-Za-z0-9_-]{2,32}$")) {
            throw new ServiceException("FREESWITCH_GATEWAY_CODE_INVALID");
        }
        return gatewayCode;
    }
}
