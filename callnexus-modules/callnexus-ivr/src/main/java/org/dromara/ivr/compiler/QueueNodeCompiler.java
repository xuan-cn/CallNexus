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
        context.xml().append("      <action application=\"set\" data=\"callnexus_node_id=")
            .append(context.freeSwitchNodeId())
            .append("\"/>\n");
        context.xml().append("      <action application=\"set\" data=\"effective_caller_id_number=${cond('${callnexus_customer_phone}' != '' ? ${callnexus_customer_phone} : ${effective_caller_id_number})}\"/>\n");
        context.xml().append("      <action application=\"set\" data=\"effective_caller_id_name=${cond('${callnexus_customer_phone}' != '' ? ${callnexus_customer_phone} : ${effective_caller_id_name})}\"/>\n");
        context.xml().append("      <action application=\"export\" data=\"callnexus_customer_phone=${callnexus_customer_phone}\"/>\n");
        if (Boolean.TRUE.equals(queue.getMaskCallerNumber())) {
            context.xml().append("      <action application=\"set\" data=\"effective_caller_id_number=anonymous\"/>\n");
            context.xml().append("      <action application=\"set\" data=\"effective_caller_id_name=匿名来电\"/>\n");
        }
        if (queue.getForceWaitSeconds() != null && queue.getForceWaitSeconds() > 0) {
            context.xml().append("      <action application=\"sleep\" data=\"")
                .append(queue.getForceWaitSeconds() * 1000)
                .append("\"/>\n");
        }
        if (queue.getForceWaitMediaPath() != null && !queue.getForceWaitMediaPath().isBlank()) {
            context.xml().append("      <action application=\"playback\" data=\"")
                .append(context.renderSupport().escape(queue.getForceWaitMediaPath()))
                .append("\"/>\n");
        }
        context.xml().append("      <action application=\"set\" data=\"callnexus_satisfaction_skip=false\"/>\n");
        context.xml().append("      <action application=\"set\" data=\"hangup_after_bridge=false\"/>\n");
        context.xml().append("      <action application=\"callcenter\" data=\"")
            .append(context.renderSupport().escape(queue.getQueueCode()))
            .append("@default\"/>\n");
        appendQueuePostTransfer(context, queue.getId());
        context.renderSupport().appendNodeEnd(context.xml());
    }

    private void appendQueuePostTransfer(IvrNodeContext context, Long queueId) {
        context.xml().append("      <action application=\"set\" data=\"callnexus_internal_transfer=QUEUE_POST\"/>\n");
        context.xml().append("      <action application=\"transfer\" data=\"callnexus_queue_post_")
            .append(queueId)
            .append(" XML ${context}\"/>\n");
    }

    private Long queueId(String value) {
        try {
            return Long.valueOf(value);
        } catch (Exception exception) {
            throw new ServiceException("请选择目标呼叫队列");
        }
    }
}
