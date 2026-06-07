package org.dromara.resource.node.domain.response;

import lombok.Data;

import java.util.Date;

@Data
public class FreeSwitchNodeResponse {
    private Long id;
    private String nodeCode;
    private String nodeName;
    private String sipDomain;
    private String wssUrl;
    private String eslHost;
    private Integer eslPort;
    private Boolean enabled;
    private Integer version;
    private Date createTime;
}
