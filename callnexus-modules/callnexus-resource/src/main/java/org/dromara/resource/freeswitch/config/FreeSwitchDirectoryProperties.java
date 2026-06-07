package org.dromara.resource.freeswitch.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "callnexus.freeswitch.directory")
public class FreeSwitchDirectoryProperties {
    private String secret;
    private String defaultTenantId = "000000";
}
