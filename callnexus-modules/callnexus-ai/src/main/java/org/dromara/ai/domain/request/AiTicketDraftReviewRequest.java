package org.dromara.ai.domain.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AiTicketDraftReviewRequest {
    @NotNull private Integer version;
    @Size(max = 500) private String reason;
}
