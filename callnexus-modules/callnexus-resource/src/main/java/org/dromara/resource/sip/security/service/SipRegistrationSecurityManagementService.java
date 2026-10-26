package org.dromara.resource.sip.security.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.resource.sip.security.domain.SipRegistrationBlock;
import org.dromara.resource.sip.security.domain.SipRegistrationSecurityEvent;
import org.dromara.resource.sip.security.domain.SipRegistrationSecurityPolicy;
import org.dromara.resource.sip.security.domain.request.SipSecurityBlockPageQuery;
import org.dromara.resource.sip.security.domain.request.SipSecurityEventPageQuery;
import org.dromara.resource.sip.security.domain.request.SipSecurityExecutionResultRequest;
import org.dromara.resource.sip.security.domain.request.SipSecurityPolicySaveRequest;
import org.dromara.resource.sip.security.domain.response.SipSecurityExecutionTaskResponse;

import java.time.LocalDateTime;
import java.util.List;

public interface SipRegistrationSecurityManagementService {
    TableDataInfo<SipRegistrationSecurityEvent> pageEvents(SipSecurityEventPageQuery query, PageQuery pageQuery);

    TableDataInfo<SipRegistrationBlock> pageBlocks(SipSecurityBlockPageQuery query, PageQuery pageQuery);

    List<SipRegistrationSecurityPolicy> listPolicies();

    SipRegistrationSecurityPolicy effectivePolicy(Long nodeId);

    Long savePolicy(SipSecurityPolicySaveRequest request);

    void releaseBlock(Long blockId, String reason);

    boolean isIpAllowlisted(Long nodeId, String sourceIp);

    void enqueueIpAction(SipRegistrationBlock block, String action);

    void notifyBlock(SipRegistrationBlock block, boolean executionFailure);

    SipSecurityExecutionTaskResponse claim(String nodeCode, String token);

    void report(Long taskId, String nodeCode, String token, SipSecurityExecutionResultRequest request);

    int historicalTriggerCount(Long nodeId, Long accountId, String sourceIp, String blockType, LocalDateTime since);
}
