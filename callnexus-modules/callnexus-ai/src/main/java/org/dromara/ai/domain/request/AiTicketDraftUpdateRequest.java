package org.dromara.ai.domain.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class AiTicketDraftUpdateRequest {
    @NotNull private Integer version;
    @Size(max = 256) private String title;
    @Size(max = 4000) private String summary;
    private Map<String, Object> formData = new LinkedHashMap<>();
}
