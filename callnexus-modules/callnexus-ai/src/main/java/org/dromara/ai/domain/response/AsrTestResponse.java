package org.dromara.ai.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.dromara.ai.provider.AsrSegment;

import java.util.List;

@Data
@AllArgsConstructor
public class AsrTestResponse {
    private String fullText;
    private List<AsrSegment> segments;
}

