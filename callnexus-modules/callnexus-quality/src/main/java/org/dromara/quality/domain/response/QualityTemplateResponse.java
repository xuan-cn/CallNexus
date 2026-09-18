package org.dromara.quality.domain.response;
import lombok.Data;
import java.util.Date;
import java.util.List;
@Data
public class QualityTemplateResponse {
    private Long id; private String templateCode; private String templateName; private String directionScope;
    private Integer totalScore; private Integer qualifiedScore; private Boolean aiReviewEnabled;
    private String status; private Long currentVersionId; private Integer currentVersionNo;
    private String remark; private Integer version; private Date createTime;
    private List<QualityTemplateItemResponse> items;
}
