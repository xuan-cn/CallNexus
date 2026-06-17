package org.dromara.system.callcenterconfig.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.dromara.system.callcenterconfig.domain.CallCenterConfigDefinition;

@InterceptorIgnore(tenantLine = "true")
public interface CallCenterConfigDefinitionMapper extends BaseMapper<CallCenterConfigDefinition> {
}
