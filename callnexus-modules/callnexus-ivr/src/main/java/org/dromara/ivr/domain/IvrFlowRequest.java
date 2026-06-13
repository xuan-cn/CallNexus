package org.dromara.ivr.domain;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class IvrFlowRequest {
    @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{2,32}$") private String flowCode;
    @NotBlank @Size(max = 64) private String flowName;
    @NotNull private Long nodeGroupId;
    @NotBlank private String draftGraphJson;
    @NotNull private Boolean enabled;
    @Size(max = 500) private String remark;
    private Integer version;
}
