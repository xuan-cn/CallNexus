package org.dromara.resource.gateway.service;

import org.dromara.resource.gateway.domain.response.FreeSwitchGatewayDirectoryResponse;

import java.util.List;

public interface FreeSwitchGatewayQueryService {
    List<FreeSwitchGatewayDirectoryResponse> findEnabledDirectoryGateways(String tenantId, String domain, String switchIpv4, String hostname);
}
