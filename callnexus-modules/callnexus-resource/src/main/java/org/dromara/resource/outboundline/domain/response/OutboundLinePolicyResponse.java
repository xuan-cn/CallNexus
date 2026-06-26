package org.dromara.resource.outboundline.domain.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
public class OutboundLinePolicyResponse {
    private Long id;
    private Long nodeId;
    private String nodeName;
    private String policyCode;
    private String policyName;
    private String policyType;
    private Boolean defaultPolicy;
    private Boolean enabled;
    private String remark;
    private Integer version;
    private Date createTime;
    private List<OutboundLinePolicyItemResponse> items = new ArrayList<>();
}
