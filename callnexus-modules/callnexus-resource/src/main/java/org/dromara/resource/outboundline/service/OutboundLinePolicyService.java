package org.dromara.resource.outboundline.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.resource.outboundline.domain.request.OutboundLinePolicyPageQuery;
import org.dromara.resource.outboundline.domain.request.OutboundLinePolicyRequest;
import org.dromara.resource.outboundline.domain.request.SkillGroupOutboundPolicyRequest;
import org.dromara.resource.outboundline.domain.response.OutboundLinePolicyResponse;
import org.dromara.resource.outboundline.domain.response.SkillGroupOutboundPolicyResponse;
import org.dromara.resource.phone.domain.response.PhoneNumberOutboundRouteResponse;

import java.util.List;

public interface OutboundLinePolicyService {
    TableDataInfo<OutboundLinePolicyResponse> page(OutboundLinePolicyPageQuery query, PageQuery pageQuery);

    OutboundLinePolicyResponse get(Long id);

    Long create(OutboundLinePolicyRequest request);

    void update(Long id, OutboundLinePolicyRequest request);

    void delete(Long id);

    PhoneNumberOutboundRouteResponse selectRoute(String tenantId, Long nodeId);

    PhoneNumberOutboundRouteResponse selectRoute(String tenantId, Long nodeId, Long agentId, Long skillGroupId);

    List<SkillGroupOutboundPolicyResponse> listSkillGroupPolicies(Long skillGroupId);

    Long saveSkillGroupPolicy(SkillGroupOutboundPolicyRequest request);

    void deleteSkillGroupPolicy(Long id);
}
