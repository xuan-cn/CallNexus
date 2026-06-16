package org.dromara.ivr.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.ivr.domain.IvrFlowVersion;
import org.dromara.ivr.mapper.IvrFlowVersionMapper;
import org.dromara.resource.businesshours.service.BusinessHoursReferenceQueryService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BusinessHoursReferenceQueryServiceImpl implements BusinessHoursReferenceQueryService {
    private final IvrFlowVersionMapper versionMapper;

    @Override
    public boolean isReferencedByPublishedIvr(String tenantId, Long planId) {
        if (planId == null) return false;
        String stringValue = "\"planId\":\"" + planId + "\"";
        String numberValue = "\"planId\":" + planId;
        return TenantHelper.dynamic(tenantId, () -> versionMapper.exists(new LambdaQueryWrapper<IvrFlowVersion>()
            .eq(IvrFlowVersion::getStatus, "PUBLISHED")
            .and(wrapper -> wrapper.like(IvrFlowVersion::getGraphJson, stringValue)
                .or().like(IvrFlowVersion::getGraphJson, numberValue))));
    }
}
