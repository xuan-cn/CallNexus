package org.dromara.resource.businesshours.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.resource.businesshours.domain.PhoneBusinessHoursRoute;
import org.dromara.resource.businesshours.domain.request.PhoneBusinessHoursRouteRequest;
import org.dromara.resource.businesshours.mapper.PhoneBusinessHoursRouteMapper;
import org.dromara.resource.ivr.service.IvrDialplanQueryService;
import org.dromara.resource.queue.service.CallQueueQueryService;
import org.dromara.resource.voicemail.service.VoiceMailBoxQueryService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PhoneBusinessHoursRouteService {
    private final PhoneBusinessHoursRouteMapper mapper;
    private final BusinessHoursQueryService businessHoursQueryService;
    private final IvrDialplanQueryService ivrDialplanQueryService;
    private final CallQueueQueryService callQueueQueryService;
    private final VoiceMailBoxQueryService voiceMailBoxQueryService;

    public PhoneBusinessHoursRoute findByPhoneNumberId(Long phoneNumberId) {
        return mapper.selectOne(new LambdaQueryWrapper<PhoneBusinessHoursRoute>()
            .eq(PhoneBusinessHoursRoute::getPhoneNumberId, phoneNumberId).last("limit 1"));
    }

    public PhoneBusinessHoursRoute require(Long id) {
        PhoneBusinessHoursRoute route = mapper.selectById(id);
        if (route == null) throw new ServiceException("号码工作时间路由不存在");
        return route;
    }

    public Long save(Long phoneNumberId, Long nodeId, PhoneBusinessHoursRouteRequest request, String tenantId) {
        if (request == null) throw new ServiceException("请配置工作时间路由");
        if (!businessHoursQueryService.isPlanAvailable(tenantId, request.getPlanId())) {
            throw new ServiceException("工作时间方案不存在或未启用");
        }
        validateTarget(request.getInHoursTargetType(), request.getInHoursTarget(), "工作时间内", tenantId, nodeId);
        validateTarget(request.getOutHoursTargetType(), request.getOutHoursTarget(), "工作时间外", tenantId, nodeId);
        PhoneBusinessHoursRoute route = findByPhoneNumberId(phoneNumberId);
        if (route == null) {
            route = new PhoneBusinessHoursRoute();
            route.setPhoneNumberId(phoneNumberId);
            apply(route, request);
            mapper.insert(route);
        } else {
            apply(route, request);
            mapper.updateById(route);
        }
        return route.getId();
    }

    public void removeByPhoneNumberId(Long phoneNumberId) {
        mapper.delete(new LambdaQueryWrapper<PhoneBusinessHoursRoute>()
            .eq(PhoneBusinessHoursRoute::getPhoneNumberId, phoneNumberId));
    }

    private void validateTarget(String type, String target, String label, String tenantId, Long nodeId) {
        if (!"HANGUP".equals(type) && StringUtils.isBlank(target)) {
            throw new ServiceException("请配置" + label + "路由目标");
        }
        try {
            if ("IVR".equals(type) && !ivrDialplanQueryService.isPublishedFlowAvailable(tenantId, Long.valueOf(target), nodeId)) {
                throw new ServiceException(label + "关联的 IVR 流程未发布或节点不可用");
            }
            if ("QUEUE".equals(type) && callQueueQueryService.findAvailableQueue(tenantId, Long.valueOf(target), nodeId) == null) {
                throw new ServiceException(label + "关联的呼叫队列未启用、未同步或节点不可用");
            }
            if ("VOICEMAIL".equals(type) && !voiceMailBoxQueryService.isAvailable(tenantId, Long.valueOf(target), nodeId)) {
                throw new ServiceException(label + "关联的语音留言箱未启用，或提示音未同步到目标节点");
            }
        } catch (NumberFormatException exception) {
            throw new ServiceException(label + "路由目标格式不合法");
        }
    }

    private void apply(PhoneBusinessHoursRoute route, PhoneBusinessHoursRouteRequest request) {
        route.setPlanId(request.getPlanId());
        route.setInHoursTargetType(request.getInHoursTargetType());
        route.setInHoursTarget(request.getInHoursTarget());
        route.setOutHoursTargetType(request.getOutHoursTargetType());
        route.setOutHoursTarget(request.getOutHoursTarget());
    }
}
