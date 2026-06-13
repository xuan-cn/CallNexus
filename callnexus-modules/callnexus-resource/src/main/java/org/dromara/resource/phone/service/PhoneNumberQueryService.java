package org.dromara.resource.phone.service;

import org.dromara.resource.phone.domain.response.PhoneNumberDialplanRouteResponse;
import org.dromara.resource.phone.domain.response.PhoneNumberOutboundRouteResponse;

public interface PhoneNumberQueryService {
    PhoneNumberDialplanRouteResponse findDialplanRoute(String tenantId, String domain, String destinationNumber);

    PhoneNumberOutboundRouteResponse findDefaultOutboundRoute(String tenantId, Long nodeId);

    PhoneNumberOutboundRouteResponse findDefaultOutboundRoute(String tenantId, String domain, String switchIpv4);
}
