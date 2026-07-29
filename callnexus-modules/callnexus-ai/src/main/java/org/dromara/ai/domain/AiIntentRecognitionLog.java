package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_intent_recognition_log")
public class AiIntentRecognitionLog extends TenantEntity {
    @TableId private Long id;
    private Long agentId;
    private Long conversationId;
    private Long messageId;
    private String inputText;
    private String normalizedText;
    private Long intentId;
    private String intentCode;
    private String intentName;
    private BigDecimal confidence;
    private String matchMethod;
    private String recognitionStatus;
    private String reason;
    private Long latencyMs;
    private Long modelId;
    private String rawResponse;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
