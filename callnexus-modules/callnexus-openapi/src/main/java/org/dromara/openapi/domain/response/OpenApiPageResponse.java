package org.dromara.openapi.domain.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record OpenApiPageResponse<T>(
    List<T> items,
    int page,
    @JsonProperty("page_size") int pageSize,
    long total
) {
}
