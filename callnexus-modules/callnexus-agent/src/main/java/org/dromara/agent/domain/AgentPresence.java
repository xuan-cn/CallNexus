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
    /**
     * 话后整理期间关联的业务通话 channel UUID。
     * 坐席挂断转 AFTER_CALL 前由通话事件处理器写入，用于按实际接听队列计算话后整理时长；
     * 整理结束恢复 IDLE 时清空。
     */
    private String handlingCallId;
}
