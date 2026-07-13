package org.dromara.resource.outboundline.domain.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SkillGroupOutboundPolicyRequest {
    @NotNull(message = "FreeSWITCH节点不能为空")
    private Long nodeId;
    @NotNull(message = "技能组不能为空")
    private Long skillGroupId;
    @NotNull(message = "外呼线路策略不能为空")
    private Long outboundLinePolicyId;
    private Boolean enabled = true;
    private String remark;
    private Integer version;
}
