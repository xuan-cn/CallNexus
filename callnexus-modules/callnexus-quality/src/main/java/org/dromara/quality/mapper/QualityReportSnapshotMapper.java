package org.dromara.quality.mapper;

import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.quality.domain.QualityReportSnapshot;

import java.util.List;

public interface QualityReportSnapshotMapper extends BaseMapperPlus<QualityReportSnapshot, QualityReportSnapshot> {
    @Select("""
        SELECT tenant_id FROM cc_quality_task WHERE deleted = 0
        UNION
        SELECT tenant_id FROM cc_quality_alert_rule WHERE deleted = 0 AND enabled = 1
        """)
    List<String> selectOperationTenantIds();
}
