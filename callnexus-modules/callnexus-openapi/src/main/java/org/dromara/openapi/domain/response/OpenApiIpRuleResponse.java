package org.dromara.openapi.domain.response;

import lombok.Data;

@Data
public class OpenApiIpRuleResponse {
    private Long id;
    private String cidr;
    private String description;
    private Boolean enabled;
}
