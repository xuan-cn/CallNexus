package org.dromara.resource.businesshours.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.ZonedDateTime;

@Data
@AllArgsConstructor
public class BusinessHoursEvaluation {
    private boolean inBusinessHours;
    private ZonedDateTime evaluatedAt;
    private String timezone;
    private String reason;
    private ZonedDateTime nextOpenTime;
}
