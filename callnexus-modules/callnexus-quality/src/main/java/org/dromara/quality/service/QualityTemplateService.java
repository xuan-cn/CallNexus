package org.dromara.quality.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.quality.domain.*;
import org.dromara.quality.domain.request.*;
import org.dromara.quality.domain.response.*;
import org.dromara.quality.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class QualityTemplateService {
    private final QualityTemplateMapper templateMapper;
    private final QualityTemplateVersionMapper versionMapper;
    private final QualityTemplateItemMapper itemMapper;
    private final QualityTaskMapper taskMapper;

    public List<QualityTemplateResponse> list() {
        return templateMapper.selectList(new LambdaQueryWrapper<QualityTemplate>()
            .orderByAsc(QualityTemplate::getTemplateName)).stream().map(this::response).toList();
    }

    public QualityTemplateResponse get(Long id) { return response(require(id)); }

    @Transactional(rollbackFor = Exception.class)
    public Long create(QualityTemplateRequest request) {
        validate(request, null);
        QualityTemplate template = new QualityTemplate();
        apply(template, request);
        template.setStatus("DRAFT");
        templateMapper.insert(template);
        replaceDraftItems(template.getId(), request.getItems());
        return template.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, QualityTemplateRequest request) {
        validate(request, id);
        QualityTemplate template = require(id);
        apply(template, request);
        template.setStatus("DRAFT");
        template.setVersion(request.getVersion());
        if (templateMapper.updateById(template) != 1) throw new ServiceException("评分模板已被其他用户修改，请刷新后重试");
        replaceDraftItems(id, request.getItems());
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        require(id);
        if (taskMapper.exists(new LambdaQueryWrapper<QualityTask>().eq(QualityTask::getTemplateId, id))) {
            throw new ServiceException("评分模板已生成质检任务，不能删除");
        }
        itemMapper.delete(new LambdaQueryWrapper<QualityTemplateItem>().eq(QualityTemplateItem::getTemplateId, id));
        templateMapper.deleteById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public Long publish(Long id) {
        QualityTemplate template = require(id);
        List<QualityTemplateItem> drafts = draftItems(id);
        if (drafts.isEmpty()) throw new ServiceException("评分模板至少需要一个评分项");
        int versionNo = Optional.ofNullable(versionMapper.selectOne(new LambdaQueryWrapper<QualityTemplateVersion>()
            .eq(QualityTemplateVersion::getTemplateId, id).orderByDesc(QualityTemplateVersion::getVersionNo).last("LIMIT 1")))
            .map(QualityTemplateVersion::getVersionNo).orElse(0) + 1;
        QualityTemplateVersion version = new QualityTemplateVersion();
        version.setTemplateId(id); version.setVersionNo(versionNo); version.setStatus("PUBLISHED");
        version.setPublishedAt(LocalDateTime.now()); version.setPublishedBy(LoginHelper.getUserId());
        version.setTemplateSnapshotJson(JsonUtils.toJsonString(response(template)));
        versionMapper.insert(version);
        for (QualityTemplateItem draft : drafts) {
            QualityTemplateItem item = copyItem(draft);
            item.setId(null); item.setTemplateVersionId(version.getId()); item.setVersion(null); item.setDeleted(null);
            itemMapper.insert(item);
        }
        template.setCurrentVersionId(version.getId()); template.setStatus("PUBLISHED");
        templateMapper.updateById(template);
        return version.getId();
    }

    public QualityTemplate requirePublished(Long id) {
        QualityTemplate template = require(id);
        if (template.getCurrentVersionId() == null) throw new ServiceException("评分模板尚未发布");
        return template;
    }

    public List<QualityTemplateItem> versionItems(Long templateId, Long versionId) {
        return itemMapper.selectList(new LambdaQueryWrapper<QualityTemplateItem>()
            .eq(QualityTemplateItem::getTemplateId, templateId)
            .eq(QualityTemplateItem::getTemplateVersionId, versionId)
            .orderByAsc(QualityTemplateItem::getSortOrder));
    }

    public Integer versionNo(Long versionId) {
        QualityTemplateVersion version = versionMapper.selectById(versionId);
        return version == null ? null : version.getVersionNo();
    }

    public QualityTemplate require(Long id) {
        QualityTemplate value = templateMapper.selectById(id);
        if (value == null) throw new ServiceException("评分模板不存在");
        return value;
    }

    private void validate(QualityTemplateRequest request, Long excludedId) {
        if (request.getQualifiedScore() > request.getTotalScore()) throw new ServiceException("合格分不能大于总分");
        if (templateMapper.exists(new LambdaQueryWrapper<QualityTemplate>()
            .eq(QualityTemplate::getTemplateCode, request.getTemplateCode())
            .ne(excludedId != null, QualityTemplate::getId, excludedId))) throw new ServiceException("模板编码已存在");
        long distinct = request.getItems().stream().map(QualityTemplateItemRequest::getItemCode).distinct().count();
        if (distinct != request.getItems().size()) throw new ServiceException("评分项编码不能重复");
    }

    private void apply(QualityTemplate target, QualityTemplateRequest source) {
        target.setTemplateCode(source.getTemplateCode()); target.setTemplateName(source.getTemplateName());
        target.setDirectionScope(source.getDirectionScope()); target.setTotalScore(source.getTotalScore());
        target.setQualifiedScore(source.getQualifiedScore()); target.setAiReviewEnabled(source.getAiReviewEnabled());
        target.setRemark(source.getRemark());
    }

    private void replaceDraftItems(Long templateId, List<QualityTemplateItemRequest> requests) {
        itemMapper.delete(new LambdaQueryWrapper<QualityTemplateItem>()
            .eq(QualityTemplateItem::getTemplateId, templateId).isNull(QualityTemplateItem::getTemplateVersionId));
        int order = 0;
        for (QualityTemplateItemRequest request : requests) {
            QualityTemplateItem item = new QualityTemplateItem();
            item.setTemplateId(templateId); item.setDimensionCode(request.getDimensionCode()); item.setDimensionName(request.getDimensionName());
            item.setItemCode(request.getItemCode()); item.setItemName(request.getItemName()); item.setItemType(request.getItemType());
            item.setScoreValue(request.getScoreValue()); item.setFatalFlag(request.getFatalFlag());
            item.setAllowNotApplicable(request.getAllowNotApplicable()); item.setEvidenceRequired(request.getEvidenceRequired());
            item.setAiReviewEnabled(request.getAiReviewEnabled()); item.setAiConfidenceThreshold(request.getAiConfidenceThreshold());
            item.setAiPromptHint(request.getAiPromptHint());
            item.setRuleDescription(request.getRuleDescription()); item.setSortOrder(request.getSortOrder() == null ? ++order : request.getSortOrder());
            itemMapper.insert(item);
        }
    }

    private List<QualityTemplateItem> draftItems(Long templateId) {
        return itemMapper.selectList(new LambdaQueryWrapper<QualityTemplateItem>()
            .eq(QualityTemplateItem::getTemplateId, templateId).isNull(QualityTemplateItem::getTemplateVersionId)
            .orderByAsc(QualityTemplateItem::getSortOrder));
    }

    private QualityTemplateItem copyItem(QualityTemplateItem source) {
        QualityTemplateItem target = new QualityTemplateItem();
        target.setTemplateId(source.getTemplateId()); target.setDimensionCode(source.getDimensionCode()); target.setDimensionName(source.getDimensionName());
        target.setItemCode(source.getItemCode()); target.setItemName(source.getItemName()); target.setItemType(source.getItemType());
        target.setScoreValue(source.getScoreValue()); target.setFatalFlag(source.getFatalFlag()); target.setAllowNotApplicable(source.getAllowNotApplicable());
        target.setEvidenceRequired(source.getEvidenceRequired()); target.setAiReviewEnabled(source.getAiReviewEnabled());
        target.setAiConfidenceThreshold(source.getAiConfidenceThreshold()); target.setAiPromptHint(source.getAiPromptHint());
        target.setRuleDescription(source.getRuleDescription()); target.setSortOrder(source.getSortOrder());
        return target;
    }

    public QualityTemplateItemResponse itemResponse(QualityTemplateItem item) {
        QualityTemplateItemResponse value = new QualityTemplateItemResponse();
        value.setId(item.getId()); value.setDimensionCode(item.getDimensionCode()); value.setDimensionName(item.getDimensionName());
        value.setItemCode(item.getItemCode()); value.setItemName(item.getItemName()); value.setItemType(item.getItemType());
        value.setScoreValue(item.getScoreValue()); value.setFatalFlag(item.getFatalFlag()); value.setAllowNotApplicable(item.getAllowNotApplicable());
        value.setEvidenceRequired(item.getEvidenceRequired()); value.setAiReviewEnabled(item.getAiReviewEnabled());
        value.setAiConfidenceThreshold(item.getAiConfidenceThreshold()); value.setAiPromptHint(item.getAiPromptHint());
        value.setRuleDescription(item.getRuleDescription()); value.setSortOrder(item.getSortOrder());
        return value;
    }

    private QualityTemplateResponse response(QualityTemplate template) {
        QualityTemplateResponse value = new QualityTemplateResponse();
        value.setId(template.getId()); value.setTemplateCode(template.getTemplateCode()); value.setTemplateName(template.getTemplateName());
        value.setDirectionScope(template.getDirectionScope()); value.setTotalScore(template.getTotalScore()); value.setQualifiedScore(template.getQualifiedScore());
        value.setAiReviewEnabled(template.getAiReviewEnabled()); value.setStatus(template.getStatus()); value.setCurrentVersionId(template.getCurrentVersionId());
        value.setRemark(template.getRemark()); value.setVersion(template.getVersion()); value.setCreateTime(template.getCreateTime());
        if (template.getCurrentVersionId() != null) {
            QualityTemplateVersion version = versionMapper.selectById(template.getCurrentVersionId());
            value.setCurrentVersionNo(version == null ? null : version.getVersionNo());
        }
        value.setItems(draftItems(template.getId()).stream().map(this::itemResponse).toList());
        return value;
    }
}
