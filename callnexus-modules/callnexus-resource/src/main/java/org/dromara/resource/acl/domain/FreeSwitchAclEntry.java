package org.dromara.resource.acl.domain;

public record FreeSwitchAclEntry(
    String action,
    String cidr,
    String description
) {
}
