package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_call_transcript_segment")
public class AiCallTranscriptSegment extends TenantEntity {
    @TableId
    private Long id;
    private Long transcriptId;
    private Long callSessionId;
    private String businessCallId;
    private String speaker;
    private String sourceType;
    private String legUuid;
    private Long agentId;
    private Integer sentenceIndex;
    private Integer startMs;
    private Integer endMs;
    private LocalDateTime messageTime;
    private String textContent;
    private Boolean finalResult;
    private BigDecimal confidence;
    @Version
    private Integer version;
    @TableLogic
    private Boolean deleted;
}
