package org.dromara.resource.node.domain.response;

import lombok.Data;

import java.util.Date;
import java.time.LocalDateTime;

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
    private Boolean agentEnabled;
    private LocalDateTime agentLastHeartbeat;
    private String agentVersion;
    private String mediaRootPath;
    private Integer version;
    private Date createTime;
}
