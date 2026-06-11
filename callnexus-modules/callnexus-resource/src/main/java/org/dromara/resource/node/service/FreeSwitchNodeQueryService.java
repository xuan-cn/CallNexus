package org.dromara.resource.node.service;

import org.dromara.resource.node.domain.response.FreeSwitchNodeConnectionResponse;

import java.util.List;

public interface FreeSwitchNodeQueryService {
    FreeSwitchNodeConnectionResponse getEnabledConnection(Long nodeId);
    List<FreeSwitchNodeConnectionResponse> listEnabledConnections();
    String findTenantId(Long nodeId);
}
