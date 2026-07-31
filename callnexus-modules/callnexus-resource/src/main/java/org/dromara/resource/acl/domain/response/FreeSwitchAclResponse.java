package org.dromara.resource.acl.domain.response;

import lombok.Data;
import org.dromara.resource.acl.domain.FreeSwitchAclEntry;

import java.util.Date;
import java.util.List;

@Data
public class FreeSwitchAclResponse {
    private Long id;
    private Long nodeId;
    private String aclCode;
    private String aclName;
    private String purpose;
    private String defaultAction;
    private List<FreeSwitchAclEntry> entries;
    private Boolean enabled;
    private Integer publishedVersionNo;
    private String syncStatus;
    private String syncError;
    private Integer version;
    private Date createTime;
    private Date updateTime;
}
