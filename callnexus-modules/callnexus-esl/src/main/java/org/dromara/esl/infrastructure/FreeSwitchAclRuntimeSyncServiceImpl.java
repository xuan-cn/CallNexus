package org.dromara.esl.infrastructure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.call.domain.EslEndpoint;
import org.dromara.resource.acl.service.FreeSwitchAclRuntimeSyncService;
import org.dromara.resource.node.domain.response.FreeSwitchNodeConnectionResponse;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class FreeSwitchAclRuntimeSyncServiceImpl implements FreeSwitchAclRuntimeSyncService {
    private final FreeSwitchNodeQueryService nodeQueryService;
    private final FreeSwitchEslCommandGateway commandGateway;

    @Override
    public void reload(Long nodeId) {
        FreeSwitchNodeConnectionResponse node = nodeQueryService.getEnabledConnection(nodeId);
        EslEndpoint endpoint = new EslEndpoint(
            node.getEslHost(), node.getEslPort(), node.getEslPassword(), node.getSipDomain());
        log.info("开始同步 FreeSWITCH ACL，nodeId={}", nodeId);
        commandGateway.executeApiCommand(endpoint, "api reloadacl");
        log.info("FreeSWITCH ACL 同步完成，nodeId={}", nodeId);
    }
}
