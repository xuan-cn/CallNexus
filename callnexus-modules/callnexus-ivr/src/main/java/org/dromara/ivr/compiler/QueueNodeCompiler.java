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
        if (Boolean.TRUE.equals(queue.getMaskCallerNumber())) {
            context.xml().append("      <action application=\"set\" data=\"effective_caller_id_number=anonymous\"/>\n");
            context.xml().append("      <action application=\"set\" data=\"effective_caller_id_name=匿名来电\"/>\n");
        }
        if (queue.getForceWaitSeconds() != null && queue.getForceWaitSeconds() > 0) {
            context.xml().append("      <action application=\"sleep\" data=\"")
                .append(queue.getForceWaitSeconds() * 1000)
                .append("\"/>\n");
        }
        context.xml().append("      <action application=\"set\" data=\"hangup_after_bridge=true\"/>\n");
        context.xml().append("      <action application=\"callcenter\" data=\"")
            .append(context.renderSupport().escape(queue.getQueueCode()))
            .append("@default\"/>\n");
        appendQueueExitAction(context, queue);
        context.renderSupport().appendNodeEnd(context.xml());
    }

    private void appendQueueExitAction(IvrNodeContext context, CallQueueDialplanResponse queue) {
        String action = queue.getTimeoutAction() == null ? "HANGUP" : queue.getTimeoutAction();
        switch (action) {
            case "CONTINUE" -> {
                context.xml().append("      <action application=\"callcenter\" data=\"")
                    .append(context.renderSupport().escape(queue.getQueueCode()))
                    .append("@default\"/>\n");
                context.renderSupport().appendHangup(context.xml(), "NORMAL_CLEARING");
            }
            case "VOICEMAIL" -> appendInternalTransfer(context, "callnexus_queue_voicemail_" + safeTarget(queue.getTimeoutTarget()));
            case "IVR" -> appendInternalTransfer(context, "callnexus_queue_ivr_" + safeTarget(queue.getTimeoutTarget()));
            case "EXTENSION" -> {
                context.xml().append("      <action application=\"bridge\" data=\"user/")
                    .append(context.renderSupport().escape(safeTarget(queue.getTimeoutTarget())))
                    .append("@")
                    .append(context.renderSupport().escape(context.sipDomain()))
                    .append("\"/>\n");
                context.renderSupport().appendHangup(context.xml(), "NORMAL_CLEARING");
            }
            case "QUEUE" -> {
                context.xml().append("      <action application=\"callcenter\" data=\"")
                    .append(context.renderSupport().escape(queue.getTimeoutTargetQueueCode()))
                    .append("@default\"/>\n");
                context.renderSupport().appendHangup(context.xml(), "NORMAL_CLEARING");
            }
            default -> context.renderSupport().appendHangup(context.xml(), "NORMAL_CLEARING");
        }
    }

    private void appendInternalTransfer(IvrNodeContext context, String destination) {
        context.xml().append("      <action application=\"set\" data=\"callnexus_internal_transfer=QUEUE\"/>\n");
        context.xml().append("      <action application=\"transfer\" data=\"")
            .append(context.renderSupport().escape(destination))
            .append(" XML ${context}\"/>\n");
    }

    private String safeTarget(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9_#*+-]", "");
    }

    private Long queueId(String value) {
        try {
            return Long.valueOf(value);
        } catch (Exception exception) {
            throw new ServiceException("请选择目标呼叫队列");
        }
    }
}
