package org.dromara.resource.sip.domain.request;

import lombok.Data;

@Data
public class SipAccountPageQuery {
    private String extension;
    private String displayName;
    private Boolean enabled;
}
