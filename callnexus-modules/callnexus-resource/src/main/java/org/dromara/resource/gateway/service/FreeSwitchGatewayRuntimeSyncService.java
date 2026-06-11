package org.dromara.resource.gateway.service;

public interface FreeSwitchGatewayRuntimeSyncService {
    void addGateway(Long nodeId, String gatewayCode);

    void updateGateway(Long nodeId, String gatewayCode);

    void removeGateway(Long nodeId, String gatewayCode);
}
