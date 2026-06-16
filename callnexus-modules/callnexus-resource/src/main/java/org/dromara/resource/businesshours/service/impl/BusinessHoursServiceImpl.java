package org.dromara.resource.businesshours.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.businesshours.domain.BusinessHoursException;
import org.dromara.resource.businesshours.domain.BusinessHoursPeriod;
import org.dromara.resource.businesshours.domain.BusinessHoursPlan;
import org.dromara.resource.businesshours.domain.PhoneBusinessHoursRoute;
import org.dromara.resource.businesshours.domain.request.BusinessHoursPlanRequest;
import org.dromara.resource.businesshours.domain.response.BusinessHoursEvaluation;
import org.dromara.resource.businesshours.domain.response.BusinessHoursPlanResponse;
import org.dromara.resource.businesshours.mapper.BusinessHoursExceptionMapper;
import org.dromara.resource.businesshours.mapper.BusinessHoursPeriodMapper;
import org.dromara.resource.businesshours.mapper.BusinessHoursPlanMapper;
import org.dromara.resource.businesshours.mapper.PhoneBusinessHoursRouteMapper;
import org.dromara.resource.businesshours.service.BusinessHoursApplicationService;
import org.dromara.resource.businesshours.service.BusinessHoursQueryService;
import org.dromara.resource.businesshours.service.BusinessHoursReferenceQueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BusinessHoursServiceImpl implements BusinessHoursApplicationService, BusinessHoursQueryService {
    private final BusinessHoursPlanMapper planMapper;
    private final BusinessHoursPeriodMapper periodMapper;
    private final BusinessHoursExceptionMapper exceptionMapper;
    private final PhoneBusinessHoursRouteMapper phoneRouteMapper;
    private final BusinessHoursReferenceQueryService referenceQueryService;

    @Override
    public List<BusinessHoursPlanResponse> list() {
        return planMapper.selectList(new LambdaQueryWrapper<BusinessHoursPlan>().orderByAsc(BusinessHoursPlan::getPlanCode))
            .stream().map(this::toResponse).toList();
    }

    @Override
    public BusinessHoursPlanResponse get(Long id) {
        return toResponse(requirePlan(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(BusinessHoursPlanRequest request) {
        validate(request, null);
        BusinessHoursPlan plan = new BusinessHoursPlan();
        apply(plan, request);
        planMapper.insert(plan);
        replaceChildren(plan.getId(), request);
        return plan.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, BusinessHoursPlanRequest request) {
        validate(request, id);
        BusinessHoursPlan plan = requirePlan(id);
        if (Boolean.TRUE.equals(plan.getEnabled()) && Boolean.FALSE.equals(request.getEnabled())) {
            rejectReferenced(id, "工作时间方案正在被号码路由引用，不能停用");
        }
        apply(plan, request);
        planMapper.updateById(plan);
        replaceChildren(id, request);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requirePlan(id);
        rejectReferenced(id, "工作时间方案正在被号码路由引用，不能删除");
        planMapper.deleteById(id);
        periodMapper.delete(new LambdaQueryWrapper<BusinessHoursPeriod>().eq(BusinessHoursPeriod::getPlanId, id));
        exceptionMapper.delete(new LambdaQueryWrapper<BusinessHoursException>().eq(BusinessHoursException::getPlanId, id));
    }

    @Override
    public BusinessHoursEvaluation evaluate(Long id, LocalDateTime evaluatedAt) {
        BusinessHoursPlan plan = requirePlan(id);
        ZoneId zone = zone(plan.getTimezone());
        Instant instant = evaluatedAt == null ? Instant.now() : evaluatedAt.atZone(zone).toInstant();
        return evaluateInternal(plan, instant);
    }

    @Override
    public boolean isPlanAvailable(String tenantId, Long planId) {
        return TenantHelper.dynamic(tenantId, () -> {
            BusinessHoursPlan plan = planMapper.selectById(planId);
            return plan != null && Boolean.TRUE.equals(plan.getEnabled());
        });
    }

    @Override
    public BusinessHoursEvaluation evaluate(String tenantId, Long planId, Instant instant) {
        return TenantHelper.dynamic(tenantId, () -> {
            BusinessHoursPlan plan = planMapper.selectById(planId);
            if (plan == null || !Boolean.TRUE.equals(plan.getEnabled())) {
                throw new ServiceException("工作时间方案不存在或未启用");
            }
            return evaluateInternal(plan, instant == null ? Instant.now() : instant);
        });
    }

    private BusinessHoursEvaluation evaluateInternal(BusinessHoursPlan plan, Instant instant) {
        ZoneId zone = zone(plan.getTimezone());
        ZonedDateTime now = instant.atZone(zone);
        LocalDate date = now.toLocalDate();
        LocalTime time = now.toLocalTime();
        List<BusinessHoursException> exceptions = exceptionMapper.selectList(new LambdaQueryWrapper<BusinessHoursException>()
            .eq(BusinessHoursException::getPlanId, plan.getId()).eq(BusinessHoursException::getExceptionDate, date));
        if (!exceptions.isEmpty()) {
            if (exceptions.stream().anyMatch(item -> "CLOSED".equals(item.getExceptionType()))) {
                return result(false, now, plan, "SPECIAL_DATE_CLOSED");
            }
            boolean open = exceptions.stream().filter(item -> "CUSTOM".equals(item.getExceptionType()))
                .anyMatch(item -> contains(item.getStartTime(), item.getEndTime(), time));
            return result(open, now, plan, open ? "SPECIAL_DATE_CUSTOM" : "SPECIAL_DATE_OUTSIDE_CUSTOM");
        }
        List<BusinessHoursPeriod> today = periods(plan.getId(), date.getDayOfWeek().getValue());
        if (today.stream().anyMatch(item -> containsOnStartDate(item.getStartTime(), item.getEndTime(), time))) {
            return result(true, now, plan, "WEEKLY_PERIOD");
        }
        List<BusinessHoursPeriod> yesterday = periods(plan.getId(), date.minusDays(1).getDayOfWeek().getValue());
        boolean previousCrossMidnight = yesterday.stream().anyMatch(item ->
            crossesMidnight(item.getStartTime(), item.getEndTime()) && time.isBefore(item.getEndTime()));
        return result(previousCrossMidnight, now, plan, previousCrossMidnight ? "WEEKLY_PERIOD_CROSS_MIDNIGHT" : "OUTSIDE_BUSINESS_HOURS");
    }

    private BusinessHoursEvaluation result(boolean open, ZonedDateTime now, BusinessHoursPlan plan, String reason) {
        return new BusinessHoursEvaluation(open, now, plan.getTimezone(), reason, null);
    }

    private boolean contains(LocalTime start, LocalTime end, LocalTime time) {
        if (start == null || end == null) return false;
        if (start.equals(end)) return true;
        if (crossesMidnight(start, end)) return !time.isBefore(start) || time.isBefore(end);
        return !time.isBefore(start) && time.isBefore(end);
    }

    private boolean containsOnStartDate(LocalTime start, LocalTime end, LocalTime time) {
        if (start == null || end == null) return false;
        if (start.equals(end)) return true;
        if (crossesMidnight(start, end)) return !time.isBefore(start);
        return !time.isBefore(start) && time.isBefore(end);
    }

    private boolean crossesMidnight(LocalTime start, LocalTime end) {
        return end.isBefore(start);
    }

    private List<BusinessHoursPeriod> periods(Long planId, int day) {
        return periodMapper.selectList(new LambdaQueryWrapper<BusinessHoursPeriod>()
            .eq(BusinessHoursPeriod::getPlanId, planId).eq(BusinessHoursPeriod::getDayOfWeek, day));
    }

    private void validate(BusinessHoursPlanRequest request, Long excludedId) {
        zone(request.getTimezone());
        boolean duplicate = planMapper.exists(new LambdaQueryWrapper<BusinessHoursPlan>()
            .eq(BusinessHoursPlan::getPlanCode, request.getPlanCode())
            .ne(excludedId != null, BusinessHoursPlan::getId, excludedId));
        if (duplicate) throw new ServiceException("工作时间方案编码已存在");
        for (BusinessHoursPlanRequest.PeriodItem item : request.getPeriods()) {
            if (item.getDayOfWeek() < 1 || item.getDayOfWeek() > 7) throw new ServiceException("星期必须在1至7之间");
        }
        for (BusinessHoursPlanRequest.ExceptionItem item : request.getExceptions()) {
            if (!List.of("CLOSED", "CUSTOM").contains(item.getExceptionType())) throw new ServiceException("特殊日期类型不合法");
            if ("CUSTOM".equals(item.getExceptionType()) && (item.getStartTime() == null || item.getEndTime() == null)) {
                throw new ServiceException("自定义特殊日期必须配置开始和结束时间");
            }
        }
    }

    private ZoneId zone(String value) {
        try {
            return ZoneId.of(value);
        } catch (Exception exception) {
            throw new ServiceException("时区格式不合法");
        }
    }

    private void rejectReferenced(Long id, String message) {
        if (phoneRouteMapper.exists(new LambdaQueryWrapper<PhoneBusinessHoursRoute>().eq(PhoneBusinessHoursRoute::getPlanId, id))) {
            throw new ServiceException(message);
        }
        if (referenceQueryService.isReferencedByPublishedIvr(LoginHelper.getTenantId(), id)) {
            throw new ServiceException("工作时间方案正在被已发布 IVR 引用，不能停用或删除");
        }
    }

    private BusinessHoursPlan requirePlan(Long id) {
        BusinessHoursPlan plan = planMapper.selectById(id);
        if (plan == null) throw new ServiceException("工作时间方案不存在");
        return plan;
    }

    private void apply(BusinessHoursPlan plan, BusinessHoursPlanRequest request) {
        plan.setPlanCode(request.getPlanCode());
        plan.setPlanName(request.getPlanName());
        plan.setTimezone(request.getTimezone());
        plan.setEnabled(request.getEnabled());
        plan.setRemark(request.getRemark());
    }

    private void replaceChildren(Long planId, BusinessHoursPlanRequest request) {
        periodMapper.delete(new LambdaQueryWrapper<BusinessHoursPeriod>().eq(BusinessHoursPeriod::getPlanId, planId));
        exceptionMapper.delete(new LambdaQueryWrapper<BusinessHoursException>().eq(BusinessHoursException::getPlanId, planId));
        int sort = 0;
        for (BusinessHoursPlanRequest.PeriodItem item : request.getPeriods()) {
            BusinessHoursPeriod period = new BusinessHoursPeriod();
            period.setPlanId(planId);
            period.setDayOfWeek(item.getDayOfWeek());
            period.setStartTime(item.getStartTime());
            period.setEndTime(item.getEndTime());
            period.setSortOrder(sort++);
            periodMapper.insert(period);
        }
        for (BusinessHoursPlanRequest.ExceptionItem item : request.getExceptions()) {
            BusinessHoursException exception = new BusinessHoursException();
            exception.setPlanId(planId);
            exception.setExceptionDate(item.getExceptionDate());
            exception.setExceptionType(item.getExceptionType());
            exception.setStartTime(item.getStartTime());
            exception.setEndTime(item.getEndTime());
            exception.setDescription(item.getDescription());
            exceptionMapper.insert(exception);
        }
    }

    private BusinessHoursPlanResponse toResponse(BusinessHoursPlan plan) {
        BusinessHoursPlanResponse response = new BusinessHoursPlanResponse();
        response.setId(plan.getId());
        response.setPlanCode(plan.getPlanCode());
        response.setPlanName(plan.getPlanName());
        response.setTimezone(plan.getTimezone());
        response.setEnabled(plan.getEnabled());
        response.setRemark(plan.getRemark());
        response.setPeriods(periodsForResponse(plan.getId()));
        response.setExceptions(exceptionsForResponse(plan.getId()));
        return response;
    }

    private List<BusinessHoursPlanRequest.PeriodItem> periodsForResponse(Long planId) {
        return periodMapper.selectList(new LambdaQueryWrapper<BusinessHoursPeriod>()
                .eq(BusinessHoursPeriod::getPlanId, planId).orderByAsc(BusinessHoursPeriod::getDayOfWeek, BusinessHoursPeriod::getSortOrder))
            .stream().map(item -> {
                BusinessHoursPlanRequest.PeriodItem result = new BusinessHoursPlanRequest.PeriodItem();
                result.setDayOfWeek(item.getDayOfWeek());
                result.setStartTime(item.getStartTime());
                result.setEndTime(item.getEndTime());
                return result;
            }).toList();
    }

    private List<BusinessHoursPlanRequest.ExceptionItem> exceptionsForResponse(Long planId) {
        return exceptionMapper.selectList(new LambdaQueryWrapper<BusinessHoursException>()
                .eq(BusinessHoursException::getPlanId, planId).orderByAsc(BusinessHoursException::getExceptionDate))
            .stream().map(item -> {
                BusinessHoursPlanRequest.ExceptionItem result = new BusinessHoursPlanRequest.ExceptionItem();
                result.setExceptionDate(item.getExceptionDate());
                result.setExceptionType(item.getExceptionType());
                result.setStartTime(item.getStartTime());
                result.setEndTime(item.getEndTime());
                result.setDescription(item.getDescription());
                return result;
            }).toList();
    }
}
