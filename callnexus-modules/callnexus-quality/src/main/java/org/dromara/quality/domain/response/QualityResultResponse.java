package org.dromara.quality.domain.response;
import lombok.Data;
import java.util.List;
@Data
public class QualityResultResponse {
    private Long id; private Integer resultVersion; private Integer totalScore; private Boolean qualified;
    private Boolean fatalFlag; private String summary; private String improvementSuggestion; private String source;
    private Long aiModelId;
    private List<QualityItemResultResponse> items;
}
