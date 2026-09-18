package org.dromara.quality.domain.response;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
public class QualityMyResultResponse extends QualityTaskResponse {
    private Long appealId;
    private String appealStatus;
    private LocalDateTime appealDeadline;
    private Boolean canAppeal;
}
