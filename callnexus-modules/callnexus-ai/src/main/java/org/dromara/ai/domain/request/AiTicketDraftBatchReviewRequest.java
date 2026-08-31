package org.dromara.ai.domain.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class AiTicketDraftBatchReviewRequest {
    @Valid
    @NotEmpty
    @Size(max = 100)
    private List<Item> items;

    @Size(max = 500)
    private String reason;

    @Data
    public static class Item {
        @NotNull
        private Long id;

        @NotNull
        private Integer version;
    }
}
