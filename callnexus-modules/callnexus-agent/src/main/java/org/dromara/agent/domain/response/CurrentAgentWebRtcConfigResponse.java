package org.dromara.agent.domain.response;

import lombok.Data;

@Data
public class CurrentAgentWebRtcConfigResponse {
    private Long agentId;
    private Long sipAccountId;
    private Long nodeId;
    private String extension;
    private String sipDisplayName;
    private String sipDomain;
    private String wssUrl;
    private String authPassword;
}
