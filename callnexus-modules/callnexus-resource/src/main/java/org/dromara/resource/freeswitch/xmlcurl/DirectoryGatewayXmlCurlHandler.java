package org.dromara.resource.freeswitch.xmlcurl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.resource.freeswitch.xml.FreeSwitchXmlRenderer;
import org.dromara.resource.freeswitch.xml.gateway.FreeSwitchGatewayXmlRenderer;
import org.dromara.resource.gateway.domain.response.FreeSwitchGatewayDirectoryResponse;
import org.dromara.resource.gateway.service.FreeSwitchGatewayQueryService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class DirectoryGatewayXmlCurlHandler implements FreeSwitchXmlCurlHandler {
    private final FreeSwitchGatewayQueryService gatewayQueryService;
    private final FreeSwitchGatewayXmlRenderer gatewayXmlRenderer;

    @Override
    public boolean supports(FreeSwitchXmlCurlRequest request) {
        return "directory".equals(request.section()) && "gateways".equals(request.purpose());
    }

    @Override
    public String handle(FreeSwitchXmlCurlRequest request) {
        String switchIpv4 = request.firstValue("FreeSWITCH-IPv4");
        String hostname = request.firstValue("FreeSWITCH-Hostname");
        if (hostname == null || hostname.isBlank()) hostname = request.firstValue("hostname");
        List<FreeSwitchGatewayDirectoryResponse> gateways = gatewayQueryService.findEnabledDirectoryGateways(
            request.tenantId(), request.domain(), switchIpv4, hostname);
        log.info("FreeSWITCH 网关目录查询完成，tenantId={}，domain={}，switchIpv4={}，hostname={}，gatewayCount={}",
            request.tenantId(), request.domain(), switchIpv4, hostname, gateways.size());
        if (gateways.isEmpty()) return FreeSwitchXmlRenderer.notFound();
        return gatewayXmlRenderer.render(gateways);
    }
}
