package org.dromara.resource.acl.domain;

import java.util.List;

public record FreeSwitchAclSnapshot(
    Long aclId,
    Long nodeId,
    String aclCode,
    String aclName,
    String purpose,
    String defaultAction,
    Boolean enabled,
    List<FreeSwitchAclEntry> entries
) {
}
