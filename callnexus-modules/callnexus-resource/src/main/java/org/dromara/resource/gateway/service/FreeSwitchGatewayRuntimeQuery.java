package org.dromara.resource.gateway.service;

import org.dromara.resource.gateway.domain.FreeSwitchGateway;
import org.dromara.resource.gateway.domain.response.FreeSwitchGatewayRuntimeResponse;

import java.util.List;

public interface FreeSwitchGatewayRuntimeQuery {
    List<FreeSwitchGatewayRuntimeResponse> query(List<FreeSwitchGateway> gateways);
}
