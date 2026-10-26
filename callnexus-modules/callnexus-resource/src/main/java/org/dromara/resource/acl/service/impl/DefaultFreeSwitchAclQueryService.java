package org.dromara.resource.acl.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.acl.domain.FreeSwitchAclSnapshot;
import org.dromara.resource.acl.domain.FreeSwitchAclVersion;
import org.dromara.resource.acl.mapper.FreeSwitchAclVersionMapper;
import org.dromara.resource.acl.service.FreeSwitchAclQueryService;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DefaultFreeSwitchAclQueryService implements FreeSwitchAclQueryService {
    private static final String SIP_ENDPOINT = "SIP_ENDPOINT";
    private static final String DEFAULT_SIP_ENDPOINT_ACL = "cnx_sip_endpoint_acl";

    private final FreeSwitchAclVersionMapper versionMapper;

    @Override
    public String findPublishedSipEndpointAclCode(String tenantId, Long nodeId) {
        if (nodeId == null) return null;
        return TenantHelper.dynamic(tenantId, () -> findForNode(nodeId));
    }

    private String findForNode(Long nodeId) {
        List<String> aclCodes = versionMapper.selectList(new LambdaQueryWrapper<FreeSwitchAclVersion>()
                .eq(FreeSwitchAclVersion::getNodeId, nodeId)
                .eq(FreeSwitchAclVersion::getCurrentVersion, true))
            .stream()
            .map(version -> JsonUtils.parseObject(version.getSnapshotJson(), FreeSwitchAclSnapshot.class))
            .filter(snapshot -> snapshot != null && Boolean.TRUE.equals(snapshot.enabled()))
            .filter(snapshot -> SIP_ENDPOINT.equalsIgnoreCase(snapshot.purpose()))
            .map(FreeSwitchAclSnapshot::aclCode)
            .filter(code -> code != null && !code.isBlank())
            .distinct()
            .sorted(Comparator.naturalOrder())
            .toList();
        if (aclCodes.isEmpty()) return null;
        if (aclCodes.size() > 1) {
            log.warn("FreeSWITCH节点存在多个已发布的SIP_ENDPOINT ACL，nodeId={}，aclCodes={}，将选择单一ACL用于Directory鉴权",
                nodeId, aclCodes);
        }
        return aclCodes.stream()
            .filter(DEFAULT_SIP_ENDPOINT_ACL::equals)
            .findFirst()
            .orElse(aclCodes.get(0));
    }
}
