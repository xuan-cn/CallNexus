package org.dromara.resource.gateway.service;

public interface FreeSwitchGatewayRuntimeSyncService {
    void refreshGateway(Long nodeId, String gatewayCode);

    void removeGateway(Long nodeId, String gatewayCode);

    void renameGateway(Long nodeId, String oldGatewayCode, String newGatewayCode);
}
