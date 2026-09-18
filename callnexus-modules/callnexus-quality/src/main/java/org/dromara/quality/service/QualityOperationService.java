package org.dromara.quality.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.quality.domain.QualityAlertRecord;
import org.dromara.quality.domain.QualityAlertRule;
import org.dromara.quality.domain.QualityReportSnapshot;
import org.dromara.quality.domain.request.QualityAlertHandleRequest;
import org.dromara.quality.domain.request.QualityAlertRuleRequest;
import org.dromara.quality.domain.request.QualityOperationQuery;
import org.dromara.quality.domain.request.QualityReportGenerateRequest;
import org.dromara.quality.domain.request.QualityReportQuery;
import org.dromara.quality.domain.response.QualityAiAdoptionSummaryResponse;
import org.dromara.quality.domain.response.QualityAppealSummaryResponse;
import org.dromara.quality.domain.response.QualityReportSummaryResponse;
import org.dromara.quality.mapper.QualityAlertRecordMapper;
import org.dromara.quality.mapper.QualityAlertRuleMapper;
import org.dromara.quality.mapper.QualityReportMapper;
import org.dromara.quality.mapper.QualityReportSnapshotMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class QualityOperationService {
    private static final Set<String> PERIOD_TYPES = Set.of("DAILY", "WEEKLY");
    private static final Set<String> METRICS = Set.of("COVERAGE_RATE", "AVERAGE_SCORE", "QUALIFIED_RATE",
        "FATAL_RATE", "AI_ADOPTION_RATE", "APPEAL_RATE");
    private static final Set<String> OPERATORS = Set.of("LT", "LTE", "GT", "GTE");
    private static final Set<String> SCOPES = Set.of("TENANT", "QUEUE", "SKILL_GROUP");

    private final QualityReportSnapshotMapper snapshotMapper;
    private final QualityAlertRuleMapper ruleMapper;
    private final QualityAlertRecordMapper recordMapper;
    private final QualityReportMapper reportMapper;

    public TableDataInfo<QualityReportSnapshot> snapshots(QualityOperationQuery query, PageQuery pageQuery) {
        Page<QualityReportSnapshot> page = snapshotMapper.selectPage(pageQuery.build(), Wrappers.<QualityReportSnapshot>lambdaQuery()
            .eq(hasText(query.getPeriodType()), QualityReportSnapshot::getPeriodType, query.getPeriodType())
            .eq(hasText(query.getScopeType()), QualityReportSnapshot::getScopeType, query.getScopeType())
            .ge(hasText(query.getBeginDate()), QualityReportSnapshot::getPeriodStart, parseDate(query.getBeginDate()))
            .le(hasText(query.getEndDate()), QualityReportSnapshot::getPeriodEnd, parseDate(query.getEndDate()))
            .like(hasText(query.getKeyword()), QualityReportSnapshot::getScopeName, query.getKeyword())
            .orderByDesc(QualityReportSnapshot::getPeriodEnd, QualityReportSnapshot::getGeneratedAt));
        return new TableDataInfo<>(page.getRecords(), page.getTotal());
    }

    public TableDataInfo<QualityAlertRule> rules(QualityOperationQuery query, PageQuery pageQuery) {
        Page<QualityAlertRule> page = ruleMapper.selectPage(pageQuery.build(), Wrappers.<QualityAlertRule>lambdaQuery()
            .eq(hasText(query.getPeriodType()), QualityAlertRule::getPeriodType, query.getPeriodType())
            .eq(hasText(query.getMetricCode()), QualityAlertRule::getMetricCode, query.getMetricCode())
            .eq(hasText(query.getScopeType()), QualityAlertRule::getScopeType, query.getScopeType())
            .like(hasText(query.getKeyword()), QualityAlertRule::getRuleName, query.getKeyword())
            .orderByDesc(QualityAlertRule::getEnabled, QualityAlertRule::getUpdateTime));
        return new TableDataInfo<>(page.getRecords(), page.getTotal());
    }

    public TableDataInfo<QualityAlertRecord> alerts(QualityOperationQuery query, PageQuery pageQuery) {
        Page<QualityAlertRecord> page = recordMapper.selectPage(pageQuery.build(), Wrappers.<QualityAlertRecord>lambdaQuery()
            .eq(hasText(query.getPeriodType()), QualityAlertRecord::getPeriodType, query.getPeriodType())
            .eq(hasText(query.getMetricCode()), QualityAlertRecord::getMetricCode, query.getMetricCode())
            .eq(hasText(query.getStatus()), QualityAlertRecord::getStatus, query.getStatus())
            .ge(hasText(query.getBeginDate()), QualityAlertRecord::getPeriodStart, parseDate(query.getBeginDate()))
            .le(hasText(query.getEndDate()), QualityAlertRecord::getPeriodEnd, parseDate(query.getEndDate()))
            .like(hasText(query.getKeyword()), QualityAlertRecord::getRuleName, query.getKeyword())
            .orderByDesc(QualityAlertRecord::getTriggeredAt));
        return new TableDataInfo<>(page.getRecords(), page.getTotal());
    }

    public Long createRule(QualityAlertRuleRequest request) {
        validateRule(request);
        QualityAlertRule rule = new QualityAlertRule();
        copyRule(request, rule);
        ruleMapper.insert(rule);
        return rule.getId();
    }

    public void updateRule(Long id, QualityAlertRuleRequest request) {
        validateRule(request);
        QualityAlertRule rule = requiredRule(id);
        copyRule(request, rule);
        if (ruleMapper.updateById(rule) != 1) throw new ServiceException("预警规则已被其他用户修改，请刷新后重试");
    }

    public void deleteRule(Long id) {
        requiredRule(id);
        ruleMapper.deleteById(id);
    }

    public void acknowledge(Long id, QualityAlertHandleRequest request) {
        QualityAlertRecord record = requiredRecord(id);
        if ("CLOSED".equals(record.getStatus())) throw new ServiceException("已关闭的预警不能再确认");
        record.setStatus("ACKNOWLEDGED");
        record.setAcknowledgedBy(LoginHelper.getUserId());
        record.setAcknowledgedName(LoginHelper.getUsername());
        record.setAcknowledgedAt(LocalDateTime.now());
        record.setHandleRemark(request.getHandleRemark());
        recordMapper.updateById(record);
    }

    public void close(Long id, QualityAlertHandleRequest request) {
        QualityAlertRecord record = requiredRecord(id);
        record.setStatus("CLOSED");
        record.setClosedBy(LoginHelper.getUserId());
        record.setClosedName(LoginHelper.getUsername());
        record.setClosedAt(LocalDateTime.now());
        record.setHandleRemark(request.getHandleRemark());
        recordMapper.updateById(record);
    }

    @Transactional(rollbackFor = Exception.class)
    public List<QualityReportSnapshot> generate(QualityReportGenerateRequest request) {
        if (!PERIOD_TYPES.contains(request.getPeriodType())) throw new ServiceException("不支持的周期类型");
        return generate(request.getPeriodType(), QualityOperationPolicy.endingAt(request.getPeriodType(), request.getPeriodEnd()));
    }

    public String executeScheduled() {
        List<String> tenantIds = TenantHelper.ignore(snapshotMapper::selectOperationTenantIds);
        int success = 0;
        int failed = 0;
        for (String tenantId : tenantIds) {
            try {
                TenantHelper.dynamic(tenantId, () -> {
                    generate("DAILY", QualityOperationPolicy.previousPeriod("DAILY", LocalDate.now()));
                    generate("WEEKLY", QualityOperationPolicy.previousPeriod("WEEKLY", LocalDate.now()));
                    return null;
                });
                success++;
            } catch (Exception e) {
                failed++;
                log.error("生成质检周期快照失败，tenantId={}", tenantId, e);
            }
        }
        return "租户=" + tenantIds.size() + "，成功=" + success + "，失败=" + failed;
    }

    @Transactional(rollbackFor = Exception.class)
    protected List<QualityReportSnapshot> generate(String periodType, QualityOperationPolicy.PeriodRange range) {
        List<QualityAlertRule> rules = ruleMapper.selectList(Wrappers.<QualityAlertRule>lambdaQuery()
            .eq(QualityAlertRule::getEnabled, true).eq(QualityAlertRule::getPeriodType, periodType));
        Map<String, ScopeValue> scopes = new LinkedHashMap<>();
        scopes.put("TENANT:0", new ScopeValue("TENANT", 0L, "全部"));
        rules.forEach(rule -> scopes.put(rule.getScopeType() + ":" + rule.getScopeId(),
            new ScopeValue(rule.getScopeType(), normalizedScopeId(rule.getScopeId()), rule.getScopeName())));
        List<QualityReportSnapshot> snapshots = new ArrayList<>();
        for (ScopeValue scope : scopes.values()) {
            QualityReportSnapshot snapshot = buildSnapshot(periodType, range, scope);
            saveSnapshot(snapshot);
            evaluateRules(snapshot, rules);
            snapshots.add(snapshot);
        }
        return snapshots;
    }

    private QualityReportSnapshot buildSnapshot(String periodType, QualityOperationPolicy.PeriodRange range, ScopeValue scope) {
        String tenantId = TenantHelper.getTenantId();
        LocalDateTime startAt = range.start().atStartOfDay();
        LocalDateTime endAt = range.end().plusDays(1).atStartOfDay();
        QualityReportQuery query = new QualityReportQuery();
        if ("QUEUE".equals(scope.type())) query.setQueueId(scope.id());
        if ("SKILL_GROUP".equals(scope.type())) query.setSkillGroupId(scope.id());
        QualityDataScopeService.Scope unrestricted = new QualityDataScopeService.Scope(false, Set.of(), Set.of());
        QualityReportSummaryResponse summary = reportMapper.selectSummary(tenantId, startAt, endAt, query, unrestricted);
        if (summary == null) summary = new QualityReportSummaryResponse();
        summary.setEligibleCallCount(reportMapper.countEligibleCalls(tenantId, startAt, endAt, query, unrestricted));
        QualityAiAdoptionSummaryResponse ai = reportMapper.selectAiAdoptionSummary(tenantId, startAt, endAt, query, unrestricted);
        QualityAppealSummaryResponse appeal = reportMapper.selectAppealSummary(tenantId, startAt, endAt, query, unrestricted);

        QualityReportSnapshot snapshot = new QualityReportSnapshot();
        snapshot.setPeriodType(periodType);
        snapshot.setPeriodStart(range.start());
        snapshot.setPeriodEnd(range.end());
        snapshot.setScopeType(scope.type());
        snapshot.setScopeId(scope.id());
        snapshot.setScopeName(scope.name());
        snapshot.setEligibleCallCount(orZero(summary.getEligibleCallCount()));
        snapshot.setReviewedCallCount(orZero(summary.getReviewedCallCount()));
        snapshot.setCoverageRate(rate(summary.getReviewedCallCount(), summary.getEligibleCallCount()));
        snapshot.setResultCount(orZero(summary.getResultCount()));
        snapshot.setAverageScore(scale(summary.getAverageScore()));
        snapshot.setQualifiedCount(orZero(summary.getQualifiedCount()));
        snapshot.setQualifiedRate(rate(summary.getQualifiedCount(), summary.getResultCount()));
        snapshot.setFatalCount(orZero(summary.getFatalCount()));
        snapshot.setFatalRate(rate(summary.getFatalCount(), summary.getResultCount()));
        snapshot.setAiAdoptionRate(ai == null ? BigDecimal.ZERO : rate(ai.getAdoptedItemCount(), ai.getEvaluatedItemCount()));
        snapshot.setAppealRate(appeal == null ? BigDecimal.ZERO : rate(appeal.getAppealedTaskCount(), appeal.getPublishedTaskCount()));
        snapshot.setGeneratedAt(LocalDateTime.now());
        return snapshot;
    }

    private void saveSnapshot(QualityReportSnapshot snapshot) {
        QualityReportSnapshot existing = snapshotMapper.selectOne(Wrappers.<QualityReportSnapshot>lambdaQuery()
            .eq(QualityReportSnapshot::getPeriodType, snapshot.getPeriodType())
            .eq(QualityReportSnapshot::getPeriodStart, snapshot.getPeriodStart())
            .eq(QualityReportSnapshot::getScopeType, snapshot.getScopeType())
            .eq(QualityReportSnapshot::getScopeId, snapshot.getScopeId()));
        if (existing == null) {
            snapshotMapper.insert(snapshot);
            return;
        }
        Long id = existing.getId();
        Integer version = existing.getVersion();
        BeanUtils.copyProperties(snapshot, existing, "id", "version", "tenantId", "createBy", "createTime");
        existing.setId(id);
        existing.setVersion(version);
        snapshotMapper.updateById(existing);
        BeanUtils.copyProperties(existing, snapshot);
    }

    private void evaluateRules(QualityReportSnapshot snapshot, List<QualityAlertRule> rules) {
        rules.stream().filter(rule -> sameScope(rule, snapshot)).forEach(rule -> {
            BigDecimal actual = metricValue(snapshot, rule.getMetricCode());
            if (!QualityOperationPolicy.breached(actual, rule.getCompareOperator(), rule.getThresholdValue())) return;
            QualityAlertRecord record = recordMapper.selectOne(Wrappers.<QualityAlertRecord>lambdaQuery()
                .eq(QualityAlertRecord::getRuleId, rule.getId()).eq(QualityAlertRecord::getSnapshotId, snapshot.getId()));
            if (record == null) {
                record = new QualityAlertRecord();
                record.setRuleId(rule.getId());
                record.setSnapshotId(snapshot.getId());
                record.setStatus("OPEN");
                record.setTriggeredAt(LocalDateTime.now());
            }
            record.setRuleName(rule.getRuleName());
            record.setPeriodType(snapshot.getPeriodType());
            record.setPeriodStart(snapshot.getPeriodStart());
            record.setPeriodEnd(snapshot.getPeriodEnd());
            record.setMetricCode(rule.getMetricCode());
            record.setActualValue(actual);
            record.setCompareOperator(rule.getCompareOperator());
            record.setThresholdValue(rule.getThresholdValue());
            record.setScopeType(snapshot.getScopeType());
            record.setScopeId(snapshot.getScopeId());
            record.setScopeName(snapshot.getScopeName());
            if (record.getId() == null) recordMapper.insert(record); else recordMapper.updateById(record);
        });
    }

    private boolean sameScope(QualityAlertRule rule, QualityReportSnapshot snapshot) {
        return rule.getScopeType().equals(snapshot.getScopeType())
            && normalizedScopeId(rule.getScopeId()).equals(snapshot.getScopeId());
    }

    private BigDecimal metricValue(QualityReportSnapshot snapshot, String metricCode) {
        return switch (metricCode) {
            case "COVERAGE_RATE" -> snapshot.getCoverageRate();
            case "AVERAGE_SCORE" -> snapshot.getAverageScore();
            case "QUALIFIED_RATE" -> snapshot.getQualifiedRate();
            case "FATAL_RATE" -> snapshot.getFatalRate();
            case "AI_ADOPTION_RATE" -> snapshot.getAiAdoptionRate();
            case "APPEAL_RATE" -> snapshot.getAppealRate();
            default -> throw new ServiceException("不支持的预警指标");
        };
    }

    private void validateRule(QualityAlertRuleRequest request) {
        if (!PERIOD_TYPES.contains(request.getPeriodType())) throw new ServiceException("不支持的周期类型");
        if (!METRICS.contains(request.getMetricCode())) throw new ServiceException("不支持的预警指标");
        if (!OPERATORS.contains(request.getCompareOperator())) throw new ServiceException("不支持的比较方式");
        if (!SCOPES.contains(request.getScopeType())) throw new ServiceException("不支持的统计范围");
        if (!"TENANT".equals(request.getScopeType()) && request.getScopeId() == null) throw new ServiceException("请选择队列或技能组");
    }

    private void copyRule(QualityAlertRuleRequest request, QualityAlertRule rule) {
        BeanUtils.copyProperties(request, rule);
        rule.setScopeId("TENANT".equals(request.getScopeType()) ? 0L : request.getScopeId());
        rule.setScopeName("TENANT".equals(request.getScopeType()) ? "全部" : request.getScopeName());
    }

    private QualityAlertRule requiredRule(Long id) {
        QualityAlertRule rule = ruleMapper.selectById(id);
        if (rule == null) throw new ServiceException("预警规则不存在");
        return rule;
    }

    private QualityAlertRecord requiredRecord(Long id) {
        QualityAlertRecord record = recordMapper.selectById(id);
        if (record == null) throw new ServiceException("预警记录不存在");
        return record;
    }

    private LocalDate parseDate(String value) {
        if (!hasText(value)) return null;
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            throw new ServiceException("日期格式必须为yyyy-MM-dd");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private Long normalizedScopeId(Long value) {
        return value == null ? 0L : value;
    }

    private long orZero(Long value) {
        return value == null ? 0L : value;
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal rate(Long numerator, Long denominator) {
        long bottom = orZero(denominator);
        if (bottom == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(orZero(numerator)).multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(bottom), 2, RoundingMode.HALF_UP);
    }

    private record ScopeValue(String type, Long id, String name) {
    }
}
