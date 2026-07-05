package org.dromara.ai.domain.request;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
@Data
public class AiFaqExtractionRequest {
    @NotNull private Long documentId;
    @NotNull private Long chatModelId;
}
