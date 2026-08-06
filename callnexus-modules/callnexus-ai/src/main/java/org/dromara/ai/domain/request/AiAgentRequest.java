package org.dromara.ai.domain.request;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class AiAgentRequest {
    @NotBlank @Size(max = 64) private String agentCode;
    @NotBlank @Size(max = 128) private String agentName;
    @Size(max = 500) private String description;
    @NotNull private Long chatModelId;
    @Size(max = 8000) private String systemPrompt;
    @Size(max = 2000) private String welcomeMessage;
    @Size(max = 16) private String voiceTransport;
    @Size(max = 256) private String voiceTransportWsUrl;
    private Boolean bargeInEnabled;
    private Boolean openingBargeInEnabled;
    @Pattern(regexp = "SENSITIVE|STANDARD|NOISY") private String bargeInMode;
    @Min(0) @Max(5000) private Integer bargeInGraceMs;
    private String retrievalMode;
    private String retrievalFailurePolicy;
    @Min(1) @Max(20) private Integer topK;
    @DecimalMin("0") @DecimalMax("1") private BigDecimal scoreThreshold;
    @DecimalMin("0") @DecimalMax("1") private BigDecimal faqScoreThreshold;
    @DecimalMin("0") @DecimalMax("2") private BigDecimal temperature;
    @Min(1) @Max(32768) private Integer maxOutputTokens;
    @Min(0) @Max(50) private Integer historyMessageLimit;
    private Boolean systemAssistant;
    private Boolean enabled;
    private List<Long> knowledgeBaseIds;
}
