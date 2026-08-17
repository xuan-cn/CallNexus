package org.dromara.resource.inbound.service;

import org.dromara.resource.freeswitch.xmlcurl.FreeSwitchXmlCurlRequest;
import org.dromara.resource.inbound.domain.response.InboundRouteMatchResponse;
import org.dromara.resource.phone.domain.response.PhoneNumberDialplanRouteResponse;

public interface InboundDidEntryQueryService {
    InboundRouteMatchResponse match(String tenantId, Long nodeId, Long gatewayId, String calledNumber,
                                    String portCode, String accountCode, String headerName, String headerValue);

    PhoneNumberDialplanRouteResponse findDialplanRoute(FreeSwitchXmlCurlRequest request, Long nodeId,
                                                       Long gatewayId, String calledNumber);
}
