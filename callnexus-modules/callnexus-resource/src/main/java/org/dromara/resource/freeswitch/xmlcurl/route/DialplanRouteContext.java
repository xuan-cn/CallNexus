package org.dromara.resource.freeswitch.xmlcurl.route;

import org.dromara.resource.freeswitch.xmlcurl.FreeSwitchXmlCurlRequest;
import org.dromara.resource.phone.domain.response.PhoneNumberDialplanRouteResponse;

public record DialplanRouteContext(
    FreeSwitchXmlCurlRequest request,
    PhoneNumberDialplanRouteResponse route,
    String dialplanContext,
    String callerNumber
) {
}
