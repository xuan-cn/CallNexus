package org.dromara.esl.infrastructure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.call.domain.EslEndpoint;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.node.domain.response.FreeSwitchNodeConnectionResponse;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** FreeSWITCH 重启或 ESL 重连后的基础运行态对账。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FreeSwitchRuntimeReconciler {
    private static final long[] RETRY_DELAYS_MILLIS = {0L, 5_000L, 15_000L, 30_000L, 60_000L};
    private static final String SOFIA_PROFILE = "external";

    private final FreeSwitchNodeQueryService nodeQueryService;
    private final FreeSwitchEslCommandGateway commandGateway;
    private final Set<Long> reconcilingNodes = ConcurrentHashMap.newKeySet();

    public void reconcileWithRetry(Long nodeId) {
        if (nodeId == null || !reconcilingNodes.add(nodeId)) {
            return;
        }
        try {
            String tenantId = nodeQueryService.findTenantId(nodeId);
            if (tenantId == null || tenantId.isBlank()) {
                log.warn("跳过 FreeSWITCH 运行态对账，节点租户不存在，nodeId={}", nodeId);
                return;
            }
            for (int attempt = 0; attempt < RETRY_DELAYS_MILLIS.length; attempt++) {
                if (!sleep(RETRY_DELAYS_MILLIS[attempt])) {
                    return;
                }
                try {
                    TenantHelper.dynamic(tenantId, () -> reconcile(nodeId));
                    return;
                } catch (Exception exception) {
                    log.warn("FreeSWITCH 运行态对账失败，将按退避策略重试，nodeId={}，attempt={}，error={}",
                        nodeId, attempt + 1, exception.getMessage());
                }
            }
            log.error("FreeSWITCH 运行态对账重试耗尽，nodeId={}，请检查动态 XML 接口和模块加载日志", nodeId);
        } finally {
            reconcilingNodes.remove(nodeId);
        }
    }

    private void reconcile(Long nodeId) {
        FreeSwitchNodeConnectionResponse node = nodeQueryService.getEnabledConnection(nodeId);
        EslEndpoint endpoint = new EslEndpoint(node.getEslHost(), node.getEslPort(), node.getEslPassword(), node.getSipDomain());

        commandGateway.executeApiCommand(endpoint, "api reloadxml");
        commandGateway.executeApiCommand(endpoint, "api sofia profile " + SOFIA_PROFILE + " rescan");

        String callcenterLoaded = commandGateway.executeApiCommandForResult(endpoint, "api module_exists mod_callcenter");
        if (!"true".equalsIgnoreCase(callcenterLoaded)) {
            log.warn("检测到 FreeSWITCH 未加载 mod_callcenter，准备自动加载，nodeId={}", nodeId);
            commandGateway.executeApiCommand(endpoint, "api load mod_callcenter");
        }
        log.info("FreeSWITCH 运行态对账完成，nodeId={}，profile={}，modCallcenterLoaded={}",
            nodeId, SOFIA_PROFILE, "true".equalsIgnoreCase(callcenterLoaded) ? "EXISTING" : "LOADED");
    }

    private boolean sleep(long millis) {
        if (millis <= 0) {
            return true;
        }
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
