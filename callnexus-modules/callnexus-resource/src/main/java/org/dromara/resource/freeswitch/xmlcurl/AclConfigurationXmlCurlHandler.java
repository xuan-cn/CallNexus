package org.dromara.resource.freeswitch.xmlcurl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.resource.acl.service.FreeSwitchAclConfigurationProvider;
import org.dromara.resource.freeswitch.xml.FreeSwitchXmlRenderer;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AclConfigurationXmlCurlHandler implements FreeSwitchXmlCurlHandler {
    private final FreeSwitchAclConfigurationProvider provider;
    private final FreeSwitchNodeQueryService nodeQueryService;

    @Override
    public boolean supports(FreeSwitchXmlCurlRequest request) {
        if (!"configuration".equals(request.section()) || !"acl".equals(request.purpose())) {
            return false;
        }
        String configurationName = request.firstValue("key_value");
        return StringUtils.isBlank(configurationName)
            || "acl.conf".equals(configurationName)
            || "acl.conf.xml".equals(configurationName);
    }

    @Override
    public String handle(FreeSwitchXmlCurlRequest request) {
        String switchIpv4 = request.firstValue("FreeSWITCH-IPv4");
        String hostname = request.firstValue("FreeSWITCH-Hostname");
        if (StringUtils.isBlank(hostname)) {
            hostname = request.firstValue("FreeSWITCH-Switchname");
        }
        if (StringUtils.isBlank(hostname)) {
            hostname = request.firstValue("hostname");
        }
        String remoteAddress = request.firstValue("_remoteAddress");
        Long nodeId;
        try {
            nodeId = nodeQueryService.resolveEnabledNodeIdByAgentToken(
                request.tenantId(), request.firstValue("_nodeCode"), request.firstValue("_nodeToken"));
            if (nodeId == null) {
                nodeId = nodeQueryService.resolveEnabledNodeId(
                    request.tenantId(), remoteAddress, switchIpv4, hostname, request.firstValue("nodeId"));
            }
        } catch (ServiceException exception) {
            log.error("识别 FreeSWITCH ACL 动态配置请求节点失败，tenantId={}，remoteAddress={}，reason={}",
                request.tenantId(), remoteAddress, exception.getMessage());
            return FreeSwitchXmlRenderer.notFound();
        }
        if (nodeId == null) {
            log.warn("无法识别 FreeSWITCH ACL 动态配置请求节点，tenantId={}，nodeCode={}，remoteAddress={}，switchIpv4={}，hostname={}",
                request.tenantId(), request.firstValue("_nodeCode"), remoteAddress, switchIpv4, hostname);
            return FreeSwitchXmlRenderer.notFound();
        }
        log.info("已识别 FreeSWITCH ACL 动态配置请求节点，tenantId={}，nodeId={}，remoteAddress={}",
            request.tenantId(), nodeId, remoteAddress);
        return provider.render(request.tenantId(), nodeId);
    }
}
