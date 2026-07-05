package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_realtime_call_turn")
public class AiRealtimeCallTurn extends TenantEntity {
    @TableId
    private Long id;
    private Long realtimeSessionId;
    private Integer sequenceNo;
    private String userText;
    private String assistantText;
    private String answerSource;
    private String turnState;
    private LocalDateTime recognizedAt;
    private LocalDateTime answeredAt;
    private LocalDateTime playbackStartedAt;
    private LocalDateTime playbackEndedAt;
    private String failureReason;
}
