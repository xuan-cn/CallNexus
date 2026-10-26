package org.dromara.resource.acl.service;

public interface FreeSwitchAclQueryService {
    String findPublishedSipEndpointAclCode(String tenantId, Long nodeId);
}
