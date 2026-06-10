package org.dromara.resource.freeswitch.xmlcurl;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.resource.freeswitch.xml.FreeSwitchXmlRenderer;
import org.dromara.resource.freeswitch.xml.directory.FreeSwitchDirectoryXmlRenderer;
import org.dromara.resource.sip.domain.response.SipDirectoryAccountResponse;
import org.dromara.resource.sip.service.SipAccountQueryService;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DirectoryUserXmlCurlHandler implements FreeSwitchXmlCurlHandler {
    private final SipAccountQueryService sipAccountQueryService;
    private final FreeSwitchDirectoryXmlRenderer directoryXmlRenderer;

    @Override
    public boolean supports(FreeSwitchXmlCurlRequest request) {
        return "directory".equals(request.section()) && StringUtils.isBlank(request.purpose());
    }

    @Override
    public String handle(FreeSwitchXmlCurlRequest request) {
        String domain = request.domain();
        String extension = request.firstValue("user");
        if (StringUtils.isBlank(extension)) extension = request.firstValue("sip_auth_username");
        if (StringUtils.isBlank(extension)) extension = request.firstValue("key_value");
        if (StringUtils.isBlank(domain) || StringUtils.isBlank(extension)) return FreeSwitchXmlRenderer.notFound();

        SipDirectoryAccountResponse account = sipAccountQueryService.findDirectoryAccount(request.tenantId(), domain, extension);
        if (account == null) return FreeSwitchXmlRenderer.notFound();
        return directoryXmlRenderer.render(account);
    }
}
