package org.dromara.resource.phone.service;

import org.dromara.resource.phone.domain.response.PhoneNumberOutboundRouteResponse;

public interface PhoneNumberQueryService {
    PhoneNumberOutboundRouteResponse findDefaultOutboundRoute(String tenantId, Long nodeId);

    PhoneNumberOutboundRouteResponse findDefaultOutboundRoute(String tenantId, String domain, String switchIpv4);

    PhoneNumberOutboundRouteResponse findOutboundRouteByNumberId(String tenantId, Long nodeId, Long numberId);
}
