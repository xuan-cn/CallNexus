package org.dromara.ai.domain.response;

import lombok.Data;

@Data
public class AiIntentGroupResponse {
    private Long id;
    private String groupCode;
    private String groupName;
    private String description;
    private Integer sortOrder;
    private Boolean enabled;
    private Long intentCount;
    private Integer version;
}
