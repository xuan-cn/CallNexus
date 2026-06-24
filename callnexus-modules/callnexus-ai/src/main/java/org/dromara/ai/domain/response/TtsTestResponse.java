package org.dromara.ai.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TtsTestResponse {
    private Long mediaId;
    private String playbackUrl;
}
