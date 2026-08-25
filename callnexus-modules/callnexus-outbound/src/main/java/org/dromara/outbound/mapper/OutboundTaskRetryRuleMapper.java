package org.dromara.outbound.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.dromara.outbound.domain.OutboundTaskRetryRule;

@Mapper
public interface OutboundTaskRetryRuleMapper extends BaseMapper<OutboundTaskRetryRule> {
}
