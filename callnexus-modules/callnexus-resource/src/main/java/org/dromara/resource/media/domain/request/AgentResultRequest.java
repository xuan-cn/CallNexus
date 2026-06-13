package org.dromara.resource.media.domain.request;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
@Data
public class AgentResultRequest {
    @NotBlank private String leaseToken;
    private Boolean success;
    private String failureReason;
}
