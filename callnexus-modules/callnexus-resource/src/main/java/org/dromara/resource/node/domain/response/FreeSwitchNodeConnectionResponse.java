package org.dromara.resource.node.domain.response;

import lombok.Data;

@Data
public class FreeSwitchNodeConnectionResponse {
    private Long nodeId;
    private String sipDomain;
    private String eslHost;
    private Integer eslPort;
    private String eslPassword;
}
