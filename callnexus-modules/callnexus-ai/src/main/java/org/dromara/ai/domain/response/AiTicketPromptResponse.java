package org.dromara.ai.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiTicketPromptResponse {
    private Long policyId;
    private Long promptVersionId;
    private Integer versionNo;
    private String versionName;
    private String status;
    private String promptContent;
    private String protocolVersion;
    private String jsonSchema;
    private String safetyConstraints;
    private List<String> availableVariables;
    private List<String> requiredVariables;
    private Boolean customPrompt;
}
