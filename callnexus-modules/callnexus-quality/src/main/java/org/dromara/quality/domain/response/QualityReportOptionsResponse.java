package org.dromara.quality.domain.response;

import lombok.Data;

import java.util.List;

@Data
public class QualityReportOptionsResponse {
    private List<QualityOptionResponse> agents;
    private List<QualityOptionResponse> queues;
    private List<QualityOptionResponse> skillGroups;
}
