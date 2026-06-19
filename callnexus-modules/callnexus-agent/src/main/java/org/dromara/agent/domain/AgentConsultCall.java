package org.dromara.agent.domain;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class AgentConsultCall implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String originalCallId;
    private String consultCallId;
    private Long agentId;
    private String agentExtension;
    private String targetExtension;
    private LocalDateTime startedAt;
}
