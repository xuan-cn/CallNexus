package org.dromara.ivr.compiler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.resource.node.group.domain.FreeSwitchNodeGroupMember;
import org.dromara.resource.node.group.mapper.FreeSwitchNodeGroupMemberMapper;
import org.dromara.resource.queue.domain.response.CallQueueDialplanResponse;
import org.dromara.resource.queue.service.CallQueueQueryService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class QueueNodeCompiler implements IvrNodeCompiler {

    private final CallQueueQueryService callQueueQueryService;
    private final FreeSwitchNodeGroupMemberMapper nodeGroupMemberMapper;

    @Override
    public String nodeType() {
        return "QUEUE";
    }

    @Override
    public void validate(IvrNodeValidationContext context) {
        Long queueId = queueId(context.node().config().path("queueId").asText());
        List<Long> nodeIds = nodeGroupMemberMapper.selectList(new LambdaQueryWrapper<FreeSwitchNodeGroupMember>()
                .eq(FreeSwitchNodeGroupMember::getGroupId, context.flow().getNodeGroupId()))
            .stream()
            .map(FreeSwitchNodeGroupMember::getNodeId)
            .distinct()
            .toList();
        if (nodeIds.isEmpty()) {
            throw new ServiceException("IVR 流程所属节点组未配置 FreeSWITCH 节点");
        }
        for (Long nodeId : nodeIds) {
            if (callQueueQueryService.findAvailableQueue(context.flow().getTenantId(), queueId, nodeId) == null) {
                throw new ServiceException("转接队列未启用、未同步或未覆盖 IVR 节点组中的全部节点");
            }
        }
        context.requireTerminal();
    }

    @Override
    public void compile(IvrNodeContext context) {
        Long queueId = queueId(context.node().config().path("queueId").asText());
        CallQueueDialplanResponse queue = callQueueQueryService.findAvailableQueue(
            context.flow().getTenantId(), queueId, context.freeSwitchNodeId());
        if (queue == null) {
            throw new ServiceException("当前 FreeSWITCH 节点无法使用目标呼叫队列");
        }
        context.renderSupport().appendNodeStart(context.xml(), context.flow().getId(), context.node());
        context.xml().append("      <action application=\"set\" data=\"callnexus_ivr_queue_id=")
            .append(queue.getId())
            .append("\"/>\n");
        context.xml().append("      <action application=\"set\" data=\"callnexus_ivr_queue_code=")
            .append(context.renderSupport().escape(queue.getQueueCode()))
            .append("\"/>\n");
        context.xml().append("      <action application=\"callcenter\" data=\"")
            .append(context.renderSupport().escape(queue.getQueueCode()))
            .append("@default\"/>\n");
        context.renderSupport().appendNodeEnd(context.xml());
    }

    private Long queueId(String value) {
        try {
            return Long.valueOf(value);
        } catch (Exception exception) {
            throw new ServiceException("请选择目标呼叫队列");
        }
    }
}
