package org.dromara.agent.domain.response;

import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
public class SkillGroupResponse {
    private Long id;
    private String groupCode;
    private String groupName;
    private List<Long> agentIds;
    private Integer memberCount;
    private Boolean enabled;
    private String remark;
    private Integer version;
    private Date createTime;
}
