package org.dromara.resource.freeswitch.xmlcurl;

import org.dromara.common.core.utils.StringUtils;
import org.springframework.util.MultiValueMap;

public class FreeSwitchXmlCurlRequest {
    private final MultiValueMap<String, String> params;
    private final String defaultTenantId;

    public FreeSwitchXmlCurlRequest(MultiValueMap<String, String> params, String defaultTenantId) {
        this.params = params;
        this.defaultTenantId = defaultTenantId;
    }

    public String firstValue(String key) {
        String value = params.getFirst(key);
        return value == null ? null : value.trim();
    }

    public String section() {
        return firstValue("section");
    }

    public String purpose() {
        return firstValue("purpose");
    }

    public String domain() {
        return firstValue("domain");
    }

    public String tenantId() {
        String tenantId = firstValue("tenantId");
        return StringUtils.isBlank(tenantId) ? defaultTenantId : tenantId;
    }
}
