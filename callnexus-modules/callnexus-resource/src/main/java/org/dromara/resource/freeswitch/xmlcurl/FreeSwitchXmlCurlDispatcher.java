package org.dromara.resource.freeswitch.xmlcurl;

import lombok.RequiredArgsConstructor;
import org.dromara.resource.freeswitch.config.FreeSwitchDirectoryProperties;
import org.dromara.resource.freeswitch.xml.FreeSwitchXmlRenderer;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;

@Component
@RequiredArgsConstructor
public class FreeSwitchXmlCurlDispatcher {
    private final FreeSwitchDirectoryProperties properties;
    private final List<FreeSwitchXmlCurlHandler> handlers;

    public String dispatch(MultiValueMap<String, String> params) {
        FreeSwitchXmlCurlRequest request = new FreeSwitchXmlCurlRequest(params, properties.getDefaultTenantId());
        return handlers.stream()
            .filter(handler -> handler.supports(request))
            .findFirst()
            .map(handler -> handler.handle(request))
            .orElseGet(FreeSwitchXmlRenderer::notFound);
    }

    public String dispatch(MultiValueMap<String, String> params, String section, String purpose) {
        MultiValueMap<String, String> normalizedParams = new LinkedMultiValueMap<>(params);
        normalizedParams.set("section", section);
        if (purpose == null) {
            normalizedParams.remove("purpose");
        } else {
            normalizedParams.set("purpose", purpose);
        }
        return dispatch(normalizedParams);
    }
}
