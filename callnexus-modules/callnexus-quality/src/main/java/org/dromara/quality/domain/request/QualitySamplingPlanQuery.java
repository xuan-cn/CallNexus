package org.dromara.quality.domain.request;

import lombok.Data;

@Data
public class QualitySamplingPlanQuery {
    private String keyword;
    private String samplingMethod;
    private String scheduleType;
    private Boolean enabled;
}
