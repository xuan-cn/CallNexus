package org.dromara.resource.node.domain.request;

import lombok.Data;

@Data
public class FreeSwitchNodePageQuery {
    private String nodeCode;
    private String nodeName;
    private Boolean enabled;
}
