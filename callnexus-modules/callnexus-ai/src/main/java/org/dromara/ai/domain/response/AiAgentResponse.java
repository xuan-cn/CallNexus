package org.dromara.ai.domain.response;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class AiAgentResponse {
    private Long id;
    private String agentCode;
    private String agentName;
    private String description;
    private Long chatModelId;
    private String chatModelName;
    private String systemPrompt;
    private String welcomeMessage;
    private String voiceTransport;
    private String voiceTransportWsUrl;
    private Boolean bargeInEnabled;
    private Boolean openingBargeInEnabled;
    private String bargeInMode;
    private Integer bargeInGraceMs;
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
    private Integer version;
    private List<Long> knowledgeBaseIds;
    private List<String> knowledgeBaseNames;
}
