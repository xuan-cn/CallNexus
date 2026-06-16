package org.dromara.resource.businesshours.domain.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class BusinessHoursPlanRequest {
    @NotBlank
    @Size(max = 64)
    private String planCode;
    @NotBlank
    @Size(max = 128)
    private String planName;
    @NotBlank
    @Size(max = 64)
    private String timezone = "Asia/Shanghai";
    @NotNull
    private Boolean enabled;
    @Size(max = 500)
    private String remark;
    @Valid
    private List<PeriodItem> periods = new ArrayList<>();
    @Valid
    private List<ExceptionItem> exceptions = new ArrayList<>();

    @Data
    public static class PeriodItem {
        @NotNull
        private Integer dayOfWeek;
        @NotNull
        private LocalTime startTime;
        @NotNull
        private LocalTime endTime;
    }

    @Data
    public static class ExceptionItem {
        @NotNull
        private LocalDate exceptionDate;
        @NotBlank
        private String exceptionType;
        private LocalTime startTime;
        private LocalTime endTime;
        @Size(max = 255)
        private String description;
    }
}
