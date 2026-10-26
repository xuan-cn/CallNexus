package org.dromara.outbound.domain.request;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

@Data
public class ClickDialCustomerQuery {
    private Integer pageNum = 1;
    private Integer pageSize = 10;
    private String keyword;
    private String customerType;
    private String sourceChannel;
    private String tags;
    private Long skillGroupId;
    private String workFilter = "UNCALLED";
    private String resultCode;
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastCalledStart;
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastCalledEnd;
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime followUpStart;
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime followUpEnd;
}
