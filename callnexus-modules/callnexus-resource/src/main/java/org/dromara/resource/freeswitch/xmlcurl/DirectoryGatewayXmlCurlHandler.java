package org.dromara.resource.freeswitch.xmlcurl;

import lombok.RequiredArgsConstructor;
import org.dromara.resource.freeswitch.xml.FreeSwitchXmlRenderer;
import org.dromara.resource.freeswitch.xml.gateway.FreeSwitchGatewayXmlRenderer;
import org.dromara.resource.gateway.domain.response.FreeSwitchGatewayDirectoryResponse;
import org.dromara.resource.gateway.service.FreeSwitchGatewayQueryService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class DirectoryGatewayXmlCurlHandler implements FreeSwitchXmlCurlHandler {
    private final FreeSwitchGatewayQueryService gatewayQueryService;
    private final FreeSwitchGatewayXmlRenderer gatewayXmlRenderer;

    @Override
    public boolean supports(FreeSwitchXmlCurlRequest request) {
        return "directory".equals(request.section()) && "gateways".equals(request.purpose());
    }

    @Override
    public String handle(FreeSwitchXmlCurlRequest request) {
        List<FreeSwitchGatewayDirectoryResponse> gateways = gatewayQueryService.findEnabledDirectoryGateways(request.tenantId(), request.domain());
        if (gateways.isEmpty()) return FreeSwitchXmlRenderer.notFound();
        return gatewayXmlRenderer.render(gateways);
    }
}
