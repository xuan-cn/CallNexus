package org.dromara.resource.sip.domain.response;

import java.util.Date;
import lombok.Data;

@Data
public class SipAccountResponse {
    private Long id;
    private String extension;
    private String displayName;
    private String domain;
    private Boolean enabled;
    private Integer version;
    private Date createTime;
}
