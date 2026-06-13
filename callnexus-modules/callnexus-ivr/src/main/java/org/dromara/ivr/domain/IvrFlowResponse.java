package org.dromara.ivr.domain;

import lombok.Data;
import java.util.Date;
import java.util.List;

@Data
public class IvrFlowResponse {
    private Long id;
    private String flowCode;
    private String flowName;
    private Long nodeGroupId;
    private String nodeGroupName;
    private List<Long> nodeIds;
    private String draftGraphJson;
    private Integer latestVersionNo;
    private String publishStatus;
    private Boolean enabled;
    private String remark;
    private Integer version;
    private Date createTime;
}
