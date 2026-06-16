package org.dromara.ivr.compiler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.resource.node.group.domain.FreeSwitchNodeGroupMember;
import org.dromara.resource.node.group.mapper.FreeSwitchNodeGroupMemberMapper;
import org.dromara.resource.voicemail.domain.response.VoiceMailDialplanResponse;
import org.dromara.resource.voicemail.service.VoiceMailBoxQueryService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class VoiceMailNodeCompiler implements IvrNodeCompiler {

    private final VoiceMailBoxQueryService voiceMailBoxQueryService;
    private final FreeSwitchNodeGroupMemberMapper nodeGroupMemberMapper;

    @Override
    public String nodeType() {
        return "VOICEMAIL";
    }

    @Override
    public void validate(IvrNodeValidationContext context) {
        Long boxId = boxId(context.node().config().path("boxId").asText());
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
            if (!voiceMailBoxQueryService.isAvailable(context.flow().getTenantId(), boxId, nodeId)) {
                throw new ServiceException("语音留言箱未启用，或提示音未同步到 IVR 节点组中的全部节点");
            }
        }
        context.requireTerminal();
    }

    @Override
    public void compile(IvrNodeContext context) {
        Long boxId = boxId(context.node().config().path("boxId").asText());
        VoiceMailDialplanResponse box = voiceMailBoxQueryService.findAvailableBox(
            context.flow().getTenantId(), boxId, context.freeSwitchNodeId());
        if (box == null) {
            throw new ServiceException("当前 FreeSWITCH 节点无法使用目标语音留言箱");
        }
        context.renderSupport().appendNodeStart(context.xml(), context.flow().getId(), context.node());
        context.xml().append("      <action application=\"set\" data=\"callnexus_ivr_voicemail_box_id=")
            .append(box.getId())
            .append("\"/>\n");
        context.xml().append("      <action application=\"playback\" data=\"")
            .append(context.renderSupport().escape(box.getPromptPath()))
            .append("\"/>\n");
        context.xml().append("      <action application=\"playback\" data=\"tone_stream://%(1000,0,640)\"/>\n");
        context.xml().append("      <action application=\"set\" data=\"callnexus_voicemail_path=/var/lib/freeswitch/recordings/${callnexus_business_call_id}-voicemail.wav\"/>\n");
        context.xml().append("      <action application=\"set\" data=\"api_hangup_hook=bg_system /opt/callnexus/bin/upload-voicemail.sh ${callnexus_business_call_id} ${callnexus_voicemail_path} ")
            .append(box.getId())
            .append(" ${caller_id_number} ${callnexus_original_called}\"/>\n");
        context.xml().append("      <action application=\"record\" data=\"${callnexus_voicemail_path} ")
            .append(box.getMaxSeconds())
            .append(" ")
            .append(box.getSilenceThreshold())
            .append(" ")
            .append(box.getSilenceHits())
            .append("\"/>\n");
        context.renderSupport().appendHangup(context.xml(), "NORMAL_CLEARING");
        context.renderSupport().appendNodeEnd(context.xml());
    }

    private Long boxId(String value) {
        try {
            return Long.valueOf(value);
        } catch (Exception exception) {
            throw new ServiceException("请选择目标语音留言箱");
        }
    }
}
