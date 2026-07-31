package org.dromara.resource.acl.domain.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class FreeSwitchAclIpTestRequest {
    @NotBlank
    private String ip;
}
