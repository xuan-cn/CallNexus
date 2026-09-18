package org.dromara.quality.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.quality.domain.QualityItemResult;
import org.dromara.quality.domain.QualityResult;
import org.dromara.quality.domain.response.QualityItemResultResponse;
import org.dromara.quality.domain.response.QualityResultResponse;
import org.dromara.quality.mapper.QualityItemResultMapper;

final class QualityResultResponseMapper {
    private QualityResultResponseMapper() {}

    static QualityResultResponse toResponse(QualityResult result, QualityItemResultMapper itemResultMapper) {
        QualityResultResponse value = new QualityResultResponse();
        value.setId(result.getId()); value.setResultVersion(result.getResultVersion()); value.setTotalScore(result.getTotalScore());
        value.setQualified(result.getQualified()); value.setFatalFlag(result.getFatalFlag()); value.setSummary(result.getSummary());
        value.setImprovementSuggestion(result.getImprovementSuggestion()); value.setSource(result.getSource()); value.setAiModelId(result.getAiModelId());
        value.setItems(itemResultMapper.selectList(new LambdaQueryWrapper<QualityItemResult>()
            .eq(QualityItemResult::getQualityResultId, result.getId())).stream().map(item -> {
                QualityItemResultResponse row = new QualityItemResultResponse();
                row.setId(item.getId()); row.setTemplateItemId(item.getTemplateItemId()); row.setItemCode(item.getItemCode()); row.setItemName(item.getItemName());
                row.setResult(item.getResult()); row.setScoreChange(item.getScoreChange()); row.setConfidence(item.getConfidence());
                row.setReason(item.getReason()); row.setEvidenceJson(item.getEvidenceJson()); row.setManuallyModified(item.getManuallyModified());
                row.setModificationReason(item.getModificationReason());
                return row;
            }).toList());
        return value;
    }
}
