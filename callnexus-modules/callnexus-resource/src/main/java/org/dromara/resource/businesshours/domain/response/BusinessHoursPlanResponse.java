package org.dromara.resource.businesshours.domain.response;

import lombok.Data;
import org.dromara.resource.businesshours.domain.request.BusinessHoursPlanRequest;

import java.util.List;

@Data
public class BusinessHoursPlanResponse {
    private Long id;
    private String planCode;
    private String planName;
    private String timezone;
    private Boolean enabled;
    private String remark;
    private List<BusinessHoursPlanRequest.PeriodItem> periods;
    private List<BusinessHoursPlanRequest.ExceptionItem> exceptions;
}
