package org.dromara.resource.acl.service;

public interface FreeSwitchAclConfigurationProvider {
    String render(String tenantId, Long nodeId);
}
