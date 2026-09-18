package org.dromara.quality.service;

import cn.hutool.core.util.IdUtil;
import com.baomidou.lock.annotation.Lock4j;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.quality.domain.QualitySamplingExecution;
import org.dromara.quality.domain.QualitySamplingPlan;
import org.dromara.quality.domain.QualityTemplate;
import org.dromara.quality.domain.request.QualitySamplingPlanQuery;
import org.dromara.quality.domain.request.QualitySamplingPlanRequest;
import org.dromara.quality.domain.response.*;
import org.dromara.quality.mapper.QualitySamplingExecutionMapper;
import org.dromara.quality.mapper.QualitySamplingPlanMapper;
import org.dromara.quality.mapper.QualityTaskMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class QualitySamplingPlanService {
    private static final Set<String> METHODS = Set.of("RANDOM_COUNT", "RANDOM_RATE", "STRATIFIED", "RISK_FIRST");
    private static final Set<String> SCHEDULES = Set.of("MANUAL", "DAILY", "WEEKLY", "MONTHLY");

    private final QualitySamplingPlanMapper planMapper;
    private final QualitySamplingExecutionMapper executionMapper;
    private final QualityTaskMapper taskMapper;
    private final QualityTemplateService templateService;
    private final QualityTaskService taskService;

    public TableDataInfo<QualitySamplingPlanResponse> page(QualitySamplingPlanQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<QualitySamplingPlan> wrapper = new LambdaQueryWrapper<QualitySamplingPlan>()
            .and(StringUtils.isNotBlank(query.getKeyword()), value -> value
                .like(QualitySamplingPlan::getPlanCode, query.getKeyword()).or()
                .like(QualitySamplingPlan::getPlanName, query.getKeyword()))
            .eq(StringUtils.isNotBlank(query.getSamplingMethod()), QualitySamplingPlan::getSamplingMethod, query.getSamplingMethod())
            .eq(StringUtils.isNotBlank(query.getScheduleType()), QualitySamplingPlan::getScheduleType, query.getScheduleType())
            .eq(query.getEnabled() != null, QualitySamplingPlan::getEnabled, query.getEnabled())
            .orderByDesc(QualitySamplingPlan::getCreateTime);
        Page<QualitySamplingPlan> page = planMapper.selectPage(pageQuery.build(), wrapper);
        return new TableDataInfo<>(page.getRecords().stream().map(this::response).toList(), page.getTotal());
    }

    public QualitySamplingPlanResponse get(Long id) {
        return response(require(id));
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(QualitySamplingPlanRequest request) {
        validate(request, null);
        QualitySamplingPlan plan = new QualitySamplingPlan();
        apply(plan, request);
        planMapper.insert(plan);
        return plan.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, QualitySamplingPlanRequest request) {
        validate(request, id);
        QualitySamplingPlan plan = require(id);
        apply(plan, request);
        plan.setVersion(request.getVersion());
        if (planMapper.updateById(plan) != 1) throw new ServiceException("抽检计划已被其他用户修改，请刷新后重试");
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        QualitySamplingPlan plan = require(id);
        if (executionMapper.exists(new LambdaQueryWrapper<QualitySamplingExecution>()
            .eq(QualitySamplingExecution::getPlanId, id).eq(QualitySamplingExecution::getStatus, "RUNNING"))) {
            throw new ServiceException("抽检计划正在执行，不能删除");
        }
        planMapper.deleteById(plan);
    }

    public TableDataInfo<QualitySamplingExecutionResponse> executions(Long planId, PageQuery pageQuery) {
        Page<QualitySamplingExecution> page = executionMapper.selectPage(pageQuery.build(),
            new LambdaQueryWrapper<QualitySamplingExecution>().eq(QualitySamplingExecution::getPlanId, planId)
                .orderByDesc(QualitySamplingExecution::getStartedAt));
        return new TableDataInfo<>(page.getRecords().stream().map(this::executionResponse).toList(), page.getTotal());
    }

    public Map<String, List<QualityOptionResponse>> options() {
        String tenantId = TenantHelper.getTenantId();
        return Map.of(
            "queues", planMapper.selectQueueOptions(tenantId),
            "skillGroups", planMapper.selectSkillGroupOptions(tenantId),
            "agents", planMapper.selectAgentOptions(tenantId)
        );
    }

    @Transactional(rollbackFor = Exception.class)
    @Lock4j(keys = {"#planId"})
    public QualitySamplingExecutionResponse execute(Long planId, String triggerType) {
        QualitySamplingPlan plan = require(planId);
        QualitySamplingExecution running = executionMapper.selectOne(new LambdaQueryWrapper<QualitySamplingExecution>()
            .eq(QualitySamplingExecution::getPlanId, planId).eq(QualitySamplingExecution::getStatus, "RUNNING")
            .ge(QualitySamplingExecution::getStartedAt, LocalDateTime.now().minusMinutes(30)).last("LIMIT 1"));
        if (running != null) throw new ServiceException("抽检计划正在执行，请勿重复触发");

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = now.minusDays(Optional.ofNullable(plan.getLookbackDays()).orElse(1));
        QualitySamplingExecution execution = new QualitySamplingExecution();
        execution.setPlanId(planId);
        execution.setExecutionCode("QE-" + IdUtil.fastSimpleUUID().substring(0, 16).toUpperCase());
        execution.setTriggerType(triggerType);
        execution.setStatus("RUNNING");
        execution.setWindowStart(windowStart);
        execution.setWindowEnd(now);
        execution.setCandidateCount(0);
        execution.setSelectedCount(0);
        execution.setTaskCount(0);
        execution.setExcludedCount(0);
        execution.setStartedAt(now);
        executionMapper.insert(execution);

        try {
            QualityTemplate template = templateService.requirePublished(plan.getTemplateId());
            List<QualitySamplingCandidate> candidates = planMapper.selectCandidates(
                TenantHelper.getTenantId(), plan, windowStart, now);
            List<QualitySamplingCandidate> selected = select(plan, candidates);
            String reviewerName = plan.getReviewerId() == null ? null
                : taskMapper.selectUserName(TenantHelper.getTenantId(), plan.getReviewerId());
            if (plan.getReviewerId() != null && reviewerName == null) throw new ServiceException("计划配置的质检员不存在或已停用");
            int taskCount = 0;
            int duplicateCount = 0;
            for (QualitySamplingCandidate candidate : selected) {
                try {
                    Long taskId = taskService.createSampled(candidate, template, planId, execution.getId(),
                        plan.getReviewerId(), reviewerName, plan.getPriority());
                    if (taskId == null) duplicateCount++; else taskCount++;
                } catch (DuplicateKeyException exception) {
                    duplicateCount++;
                }
            }
            execution.setCandidateCount(candidates.size());
            execution.setSelectedCount(selected.size());
            execution.setTaskCount(taskCount);
            execution.setExcludedCount(Math.max(0, candidates.size() - selected.size()) + duplicateCount);
            execution.setExclusionSummary(summary(plan, candidates.size(), selected.size(), duplicateCount));
            execution.setStatus("SUCCESS");
            execution.setFinishedAt(LocalDateTime.now());
            executionMapper.updateById(execution);
            plan.setLastExecutedAt(now);
            planMapper.updateById(plan);
            return executionResponse(execution);
        } catch (Exception exception) {
            execution.setStatus("FAILED");
            execution.setErrorMessage(StringUtils.substring(exception.getMessage(), 0, 2000));
            execution.setFinishedAt(LocalDateTime.now());
            executionMapper.updateById(execution);
            plan.setLastExecutedAt(now);
            planMapper.updateById(plan);
            log.error("质检抽检计划执行失败，planId={}, executionId={}", planId, execution.getId(), exception);
            return executionResponse(execution);
        }
    }

    public String executeScheduled() {
        LocalDateTime now = LocalDateTime.now();
        List<QualitySamplingPlan> plans = TenantHelper.ignore(() -> planMapper.selectList(
            new LambdaQueryWrapper<QualitySamplingPlan>().eq(QualitySamplingPlan::getEnabled, true)
                .ne(QualitySamplingPlan::getScheduleType, "MANUAL")));
        int due = 0;
        int success = 0;
        int failed = 0;
        for (QualitySamplingPlan plan : plans) {
            if (!due(plan, now)) continue;
            due++;
            QualitySamplingExecutionResponse result = TenantHelper.dynamic(plan.getTenantId(),
                () -> execute(plan.getId(), "SCHEDULED"));
            if ("SUCCESS".equals(result.getStatus())) success++; else failed++;
        }
        return "扫描计划=" + plans.size() + "，到期=" + due + "，成功=" + success + "，失败=" + failed;
    }

    private List<QualitySamplingCandidate> select(QualitySamplingPlan plan, List<QualitySamplingCandidate> source) {
        List<QualitySamplingCandidate> candidates = new ArrayList<>(source);
        int target = target(plan, candidates.size());
        if (target <= 0) return List.of();
        if ("RISK_FIRST".equals(plan.getSamplingMethod())) {
            candidates.sort(Comparator.comparing(QualitySamplingCandidate::getRiskScore,
                Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(QualitySamplingCandidate::getEndedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
            return new ArrayList<>(candidates.subList(0, Math.min(target, candidates.size())));
        }
        if ("STRATIFIED".equals(plan.getSamplingMethod())) return stratified(plan, candidates, target);
        Collections.shuffle(candidates);
        return new ArrayList<>(candidates.subList(0, Math.min(target, candidates.size())));
    }

    private List<QualitySamplingCandidate> stratified(QualitySamplingPlan plan,
                                                       List<QualitySamplingCandidate> candidates, int target) {
        Function<QualitySamplingCandidate, Long> classifier = "SKILL_GROUP".equals(plan.getStratifyDimension())
            ? candidate -> Optional.ofNullable(candidate.getSkillGroupId()).orElse(-1L)
            : candidate -> Optional.ofNullable(candidate.getAgentId()).orElse(-1L);
        Map<Long, List<QualitySamplingCandidate>> groups = candidates.stream()
            .collect(Collectors.groupingBy(classifier, LinkedHashMap::new, Collectors.toCollection(ArrayList::new)));
        groups.values().forEach(Collections::shuffle);
        List<QualitySamplingCandidate> selected = new ArrayList<>();
        int minimum = Optional.ofNullable(plan.getMinPerAgent()).orElse(0);
        int maximum = Optional.ofNullable(plan.getMaxPerAgent()).orElse(Integer.MAX_VALUE);
        Map<Long, Integer> counts = new HashMap<>();
        for (Map.Entry<Long, List<QualitySamplingCandidate>> entry : groups.entrySet()) {
            int count = Math.min(Math.min(minimum, maximum), entry.getValue().size());
            for (int i = 0; i < count && selected.size() < target; i++) selected.add(entry.getValue().remove(0));
            counts.put(entry.getKey(), count);
        }
        boolean added = true;
        while (selected.size() < target && added) {
            added = false;
            for (Map.Entry<Long, List<QualitySamplingCandidate>> entry : groups.entrySet()) {
                if (selected.size() >= target) break;
                int count = counts.getOrDefault(entry.getKey(), 0);
                if (count < maximum && !entry.getValue().isEmpty()) {
                    selected.add(entry.getValue().remove(0));
                    counts.put(entry.getKey(), count + 1);
                    added = true;
                }
            }
        }
        return selected;
    }

    private int target(QualitySamplingPlan plan, int size) {
        if ("RANDOM_RATE".equals(plan.getSamplingMethod())) {
            BigDecimal rate = Optional.ofNullable(plan.getSampleRate()).orElse(BigDecimal.ZERO);
            return Math.min(size, BigDecimal.valueOf(size).multiply(rate).divide(BigDecimal.valueOf(100), 0, RoundingMode.CEILING).intValue());
        }
        return Math.min(size, Optional.ofNullable(plan.getSampleCount()).orElse(size));
    }

    private boolean due(QualitySamplingPlan plan, LocalDateTime now) {
        if (!Boolean.TRUE.equals(plan.getEnabled()) || "MANUAL".equals(plan.getScheduleType())) return false;
        LocalTime scheduleTime = Optional.ofNullable(plan.getScheduleTime()).orElse(LocalTime.MIDNIGHT);
        if (now.toLocalTime().isBefore(scheduleTime)) return false;
        if (plan.getLastExecutedAt() != null && !plan.getLastExecutedAt().toLocalDate().isBefore(now.toLocalDate())) return false;
        return switch (plan.getScheduleType()) {
            case "DAILY" -> true;
            case "WEEKLY" -> Objects.equals(plan.getScheduleDay(), now.getDayOfWeek().getValue());
            case "MONTHLY" -> Objects.equals(plan.getScheduleDay(), now.getDayOfMonth());
            default -> false;
        };
    }

    private void validate(QualitySamplingPlanRequest request, Long excludedId) {
        if (!METHODS.contains(request.getSamplingMethod())) throw new ServiceException("不支持的抽样方式");
        if (!SCHEDULES.contains(request.getScheduleType())) throw new ServiceException("不支持的执行周期");
        if ("RANDOM_RATE".equals(request.getSamplingMethod()) && request.getSampleRate() == null) throw new ServiceException("按比例抽样必须填写抽样比例");
        if (!"RANDOM_RATE".equals(request.getSamplingMethod()) && request.getSampleCount() == null) throw new ServiceException("当前抽样方式必须填写抽样数量");
        if ("STRATIFIED".equals(request.getSamplingMethod()) && !Set.of("AGENT", "SKILL_GROUP").contains(request.getStratifyDimension())) {
            throw new ServiceException("分层抽样必须选择按坐席或技能组分层");
        }
        if (!"MANUAL".equals(request.getScheduleType()) && request.getScheduleTime() == null) throw new ServiceException("周期执行必须设置执行时间");
        if ("WEEKLY".equals(request.getScheduleType()) && (request.getScheduleDay() == null || request.getScheduleDay() > 7)) throw new ServiceException("每周执行必须选择星期");
        if ("MONTHLY".equals(request.getScheduleType()) && request.getScheduleDay() == null) throw new ServiceException("每月执行必须填写日期");
        if (request.getMaxDurationSeconds() != null && request.getMinDurationSeconds() != null
            && request.getMaxDurationSeconds() < request.getMinDurationSeconds()) throw new ServiceException("最大通话时长不能小于最小时长");
        if (request.getMaxPerAgent() != null && request.getMaxPerAgent() < request.getMinPerAgent()) throw new ServiceException("每坐席上限不能小于下限");
        templateService.requirePublished(request.getTemplateId());
        if (planMapper.exists(new LambdaQueryWrapper<QualitySamplingPlan>()
            .eq(QualitySamplingPlan::getPlanCode, request.getPlanCode())
            .ne(excludedId != null, QualitySamplingPlan::getId, excludedId))) throw new ServiceException("计划编码已存在");
    }

    private void apply(QualitySamplingPlan target, QualitySamplingPlanRequest source) {
        target.setPlanCode(source.getPlanCode()); target.setPlanName(source.getPlanName()); target.setTemplateId(source.getTemplateId());
        target.setSamplingMethod(source.getSamplingMethod()); target.setSampleCount(source.getSampleCount()); target.setSampleRate(source.getSampleRate());
        target.setStratifyDimension(source.getStratifyDimension()); target.setDirectionScope(source.getDirectionScope()); target.setQueueId(source.getQueueId());
        target.setSkillGroupId(source.getSkillGroupId()); target.setAgentId(source.getAgentId()); target.setMinDurationSeconds(source.getMinDurationSeconds());
        target.setMaxDurationSeconds(source.getMaxDurationSeconds()); target.setRequireRecording(source.getRequireRecording());
        target.setRequireTranscript(source.getRequireTranscript()); target.setLookbackDays(source.getLookbackDays()); target.setMinPerAgent(source.getMinPerAgent());
        target.setMaxPerAgent(source.getMaxPerAgent()); target.setCooldownDays(source.getCooldownDays()); target.setExcludeSampled(source.getExcludeSampled());
        target.setReviewerId(source.getReviewerId()); target.setPriority(source.getPriority()); target.setScheduleType(source.getScheduleType());
        target.setScheduleTime(source.getScheduleTime()); target.setScheduleDay(source.getScheduleDay()); target.setEnabled(source.getEnabled()); target.setRemark(source.getRemark());
    }

    private QualitySamplingPlan require(Long id) {
        QualitySamplingPlan plan = planMapper.selectById(id);
        if (plan == null) throw new ServiceException("抽检计划不存在");
        return plan;
    }

    private String summary(QualitySamplingPlan plan, int candidates, int selected, int duplicates) {
        return "符合条件=" + candidates + "，按" + plan.getSamplingMethod() + "选中=" + selected
            + "，重复跳过=" + duplicates + "，未入选=" + Math.max(0, candidates - selected);
    }

    private QualitySamplingPlanResponse response(QualitySamplingPlan plan) {
        QualitySamplingPlanResponse value = new QualitySamplingPlanResponse();
        value.setId(plan.getId()); value.setPlanCode(plan.getPlanCode()); value.setPlanName(plan.getPlanName()); value.setTemplateId(plan.getTemplateId());
        value.setSamplingMethod(plan.getSamplingMethod()); value.setSampleCount(plan.getSampleCount()); value.setSampleRate(plan.getSampleRate());
        value.setStratifyDimension(plan.getStratifyDimension()); value.setDirectionScope(plan.getDirectionScope()); value.setQueueId(plan.getQueueId());
        value.setSkillGroupId(plan.getSkillGroupId()); value.setAgentId(plan.getAgentId()); value.setMinDurationSeconds(plan.getMinDurationSeconds());
        value.setMaxDurationSeconds(plan.getMaxDurationSeconds()); value.setRequireRecording(plan.getRequireRecording()); value.setRequireTranscript(plan.getRequireTranscript());
        value.setLookbackDays(plan.getLookbackDays()); value.setMinPerAgent(plan.getMinPerAgent()); value.setMaxPerAgent(plan.getMaxPerAgent());
        value.setCooldownDays(plan.getCooldownDays()); value.setExcludeSampled(plan.getExcludeSampled()); value.setReviewerId(plan.getReviewerId());
        value.setPriority(plan.getPriority()); value.setScheduleType(plan.getScheduleType()); value.setScheduleTime(plan.getScheduleTime());
        value.setScheduleDay(plan.getScheduleDay()); value.setEnabled(plan.getEnabled()); value.setLastExecutedAt(plan.getLastExecutedAt());
        value.setRemark(plan.getRemark()); value.setVersion(plan.getVersion()); value.setCreateTime(plan.getCreateTime());
        try { value.setTemplateName(templateService.require(plan.getTemplateId()).getTemplateName()); } catch (Exception ignored) { }
        value.setQueueName(optionName(planMapper.selectQueueOptions(TenantHelper.getTenantId()), plan.getQueueId()));
        value.setSkillGroupName(optionName(planMapper.selectSkillGroupOptions(TenantHelper.getTenantId()), plan.getSkillGroupId()));
        value.setAgentName(optionName(planMapper.selectAgentOptions(TenantHelper.getTenantId()), plan.getAgentId()));
        value.setReviewerName(plan.getReviewerId() == null ? null : taskMapper.selectUserName(TenantHelper.getTenantId(), plan.getReviewerId()));
        return value;
    }

    private String optionName(List<QualityOptionResponse> options, Long id) {
        if (id == null) return null;
        return options.stream().filter(option -> id.equals(option.getId())).map(QualityOptionResponse::getName).findFirst().orElse(null);
    }

    private QualitySamplingExecutionResponse executionResponse(QualitySamplingExecution source) {
        QualitySamplingExecutionResponse value = new QualitySamplingExecutionResponse();
        value.setId(source.getId()); value.setPlanId(source.getPlanId()); value.setExecutionCode(source.getExecutionCode()); value.setTriggerType(source.getTriggerType());
        value.setStatus(source.getStatus()); value.setWindowStart(source.getWindowStart()); value.setWindowEnd(source.getWindowEnd());
        value.setCandidateCount(source.getCandidateCount()); value.setSelectedCount(source.getSelectedCount()); value.setTaskCount(source.getTaskCount());
        value.setExcludedCount(source.getExcludedCount()); value.setExclusionSummary(source.getExclusionSummary()); value.setErrorMessage(source.getErrorMessage());
        value.setStartedAt(source.getStartedAt()); value.setFinishedAt(source.getFinishedAt()); value.setCreateTime(source.getCreateTime());
        return value;
    }
}
