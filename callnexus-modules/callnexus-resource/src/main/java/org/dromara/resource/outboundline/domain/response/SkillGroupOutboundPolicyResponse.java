package org.dromara.resource.outboundline.domain.response;

import lombok.Data;

import java.util.Date;

@Data
public class SkillGroupOutboundPolicyResponse {
    private Long id;
    private Long nodeId;
    private String nodeName;
    private Long skillGroupId;
    private String skillGroupName;
    private Long outboundLinePolicyId;
    private String policyCode;
    private String policyName;
    private String policyType;
    private Boolean enabled;
    private String remark;
    private Integer version;
    private Date createTime;
}
