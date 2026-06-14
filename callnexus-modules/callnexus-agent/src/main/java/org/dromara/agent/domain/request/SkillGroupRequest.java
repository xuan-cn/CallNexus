package org.dromara.agent.domain.request;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Data
public class SkillGroupRequest {
    @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{2,32}$") private String groupCode;
    @NotBlank @Size(max = 64) private String groupName;
    @NotEmpty private List<Long> agentIds;
    @NotNull private Boolean enabled;
    @Size(max = 500) private String remark;
    private Integer version;
}
