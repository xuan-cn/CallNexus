package org.dromara.resource.node.group.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class NodeGroupRequest {
    @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{2,32}$") private String groupCode;
    @NotBlank @Size(max = 64) private String groupName;
    @NotEmpty private List<Long> nodeIds;
    @NotNull private Boolean enabled;
    @Size(max = 500) private String remark;
    private Integer version;
}
