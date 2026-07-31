package org.dromara.resource.acl.domain.request;

import lombok.Data;

@Data
public class FreeSwitchAclPageQuery {
    private Long nodeId;
    private String aclName;
    private String purpose;
    private Boolean enabled;
}
