package org.dromara.resource.node.domain.response;

import lombok.Data;

@Data
public class FreeSwitchNodeSipProfilePreviewResponse {
    private Long nodeId;
    private String nodeName;
    private String profileName;
    private String xmlSnippet;
    private String applyCommands;
}
