package org.dromara.quality.domain.response;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.ai.domain.response.AiCallTranscriptResponse;
import org.dromara.call.domain.response.CallRecordResponse;
import java.util.List;
@Data
@EqualsAndHashCode(callSuper = true)
public class QualityTaskDetailResponse extends QualityTaskResponse {
    private CallRecordResponse call;
    private AiCallTranscriptResponse transcript;
    private List<QualityTemplateItemResponse> templateItems;
    private QualityResultResponse result;
    private QualityResultResponse aiResult;
    private String aiReviewStatus;
    private String aiReviewError;
}
