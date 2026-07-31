package org.dromara.resource.acl.domain.response;

public record FreeSwitchAclIpTestResponse(
    String ip,
    boolean allowed,
    String matchedAction,
    String matchedCidr,
    String explanation
) {
}
