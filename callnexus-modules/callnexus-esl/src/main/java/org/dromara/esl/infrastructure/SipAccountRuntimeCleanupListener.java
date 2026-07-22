package org.dromara.esl.infrastructure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.call.domain.EslEndpoint;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.event.sip.SipAccountDeletedEvent;
import org.dromara.resource.node.domain.response.FreeSwitchNodeConnectionResponse;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 在 SIP 分机删除事务提交后清理 FreeSWITCH 运行时注册。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SipAccountRuntimeCleanupListener {

    private final FreeSwitchNodeQueryService nodeQueryService;
    private final FreeSwitchEslCommandGateway commandGateway;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSipAccountDeleted(SipAccountDeletedEvent event) {
        try {
            TenantHelper.dynamic(event.tenantId(), () -> {
                flushRegistrations(event);
                return null;
            });
        } catch (Exception exception) {
            log.warn("SIP 分机已删除，但清理 FreeSWITCH 注册失败，tenantId={}，nodeId={}，extension={}，authUsername={}，error={}",
                event.tenantId(), event.nodeId(), event.extension(), event.authUsername(), exception.getMessage());
        }
    }

    private void flushRegistrations(SipAccountDeletedEvent event) {
        FreeSwitchNodeConnectionResponse node = nodeQueryService.getEnabledConnection(event.nodeId());
        EslEndpoint endpoint = new EslEndpoint(
            node.getEslHost(), node.getEslPort(), node.getEslPassword(), node.getSipDomain());
        String domain = firstNonBlank(event.domain(), node.getSipDomain());

        Set<String> identities = new LinkedHashSet<>();
        addIfPresent(identities, event.extension());
        addIfPresent(identities, event.authUsername());
        for (String identity : identities) {
            commandGateway.flushInboundRegistration(endpoint, identity, domain);
        }
        log.info("SIP 分机删除后已清理 FreeSWITCH 注册，tenantId={}，nodeId={}，extension={}，identities={}",
            event.tenantId(), event.nodeId(), event.extension(), identities);
    }

    private void addIfPresent(Set<String> identities, String identity) {
        if (identity != null && !identity.isBlank()) {
            identities.add(identity.trim());
        }
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
