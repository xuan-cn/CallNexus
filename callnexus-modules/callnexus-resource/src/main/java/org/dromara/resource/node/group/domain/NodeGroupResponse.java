package org.dromara.resource.node.group.domain;

import lombok.Data;
import java.util.Date;
import java.util.List;

@Data
public class NodeGroupResponse {
    private Long id;
    private String groupCode;
    private String groupName;
    private List<Long> nodeIds;
    private Integer memberCount;
    private Boolean enabled;
    private String remark;
    private Integer version;
    private Date createTime;
}
