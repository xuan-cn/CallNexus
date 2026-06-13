package org.dromara.resource.ivr.service;

public interface IvrDialplanQueryService {
    boolean isPublishedFlowAvailable(String tenantId, Long flowId, Long nodeId);

    String renderPublishedFlow(String tenantId, Long flowId, Long nodeId, String number, String context, String sipDomain);
}
