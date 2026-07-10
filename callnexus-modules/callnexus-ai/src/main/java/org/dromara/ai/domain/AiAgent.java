package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_agent")
public class AiAgent extends TenantEntity {
    @TableId private Long id;
    private String agentCode;
    private String agentName;
    private String description;
    private Long chatModelId;
    private String systemPrompt;
    private String welcomeMessage;
    private String voiceTransport;
    private String voiceTransportWsUrl;
    private String retrievalMode;
    private String retrievalFailurePolicy;
    private Integer topK;
    private BigDecimal scoreThreshold;
    private BigDecimal faqScoreThreshold;
    private BigDecimal temperature;
    private Integer maxOutputTokens;
    private Integer historyMessageLimit;
    private Boolean systemAssistant;
    private Boolean enabled;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
