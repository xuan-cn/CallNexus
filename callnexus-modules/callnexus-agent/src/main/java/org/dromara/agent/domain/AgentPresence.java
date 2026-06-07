package org.dromara.agent.domain;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class AgentPresence implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long agentId;
    private AgentPresenceStatus status;
    private LocalDateTime signedInAt;
    private LocalDateTime updatedAt;
}
