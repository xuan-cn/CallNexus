package org.dromara.call.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.call.domain.CallEvent;
import org.dromara.call.domain.CallSession;
import org.dromara.call.domain.VoiceMailMessage;
import org.dromara.call.domain.request.VoiceMailHandleRequest;
import org.dromara.call.domain.request.VoiceMailMessagePageQuery;
import org.dromara.call.domain.response.VoiceMailMessageResponse;
import org.dromara.call.mapper.CallEventMapper;
import org.dromara.call.mapper.CallSessionMapper;
import org.dromara.call.mapper.VoiceMailMessageMapper;
import org.dromara.call.service.BusinessAssociationQueryService;
import org.dromara.call.service.VoiceMailMessageApplicationService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.OssService;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.media.domain.response.MediaAssetResponse;
import org.dromara.resource.media.service.MediaAssetApplicationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Date;

@Service
@RequiredArgsConstructor
@Slf4j
public class VoiceMailMessageApplicationServiceImpl implements VoiceMailMessageApplicationService {
    private static final Duration PLAYBACK_URL_TTL = Duration.ofHours(2);

    private final VoiceMailMessageMapper mapper;
    private final CallSessionMapper sessionMapper;
    private final CallEventMapper eventMapper;
    private final MediaAssetApplicationService mediaAssetService;
    private final OssService ossService;
    private final BusinessAssociationQueryService businessAssociationQueryService;

    @Override
    public TableDataInfo<VoiceMailMessageResponse> page(VoiceMailMessagePageQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<VoiceMailMessage> wrapper = new LambdaQueryWrapper<VoiceMailMessage>()
            .eq(query.getVoicemailBoxId() != null, VoiceMailMessage::getVoicemailBoxId, query.getVoicemailBoxId())
            .like(StringUtils.isNotBlank(query.getCallerNumber()), VoiceMailMessage::getCallerNumber, query.getCallerNumber())
            .like(StringUtils.isNotBlank(query.getCalledNumber()), VoiceMailMessage::getCalledNumber, query.getCalledNumber())
            .eq(StringUtils.isNotBlank(query.getStatus()), VoiceMailMessage::getStatus, query.getStatus())
            .orderByDesc(VoiceMailMessage::getCreateTime);
        Page<VoiceMailMessage> page = mapper.selectPage(pageQuery.build(), wrapper);
        return new TableDataInfo<>(page.getRecords().stream().map(item -> toResponse(item, false)).toList(), page.getTotal());
    }

    @Override
    public VoiceMailMessageResponse get(Long id) {
        VoiceMailMessage message = mapper.selectById(id);
        if (message == null) {
            throw new ServiceException("语音留言不存在");
        }
        return toResponse(message, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handle(Long id, VoiceMailHandleRequest request) {
        VoiceMailMessage message = mapper.selectById(id);
        if (message == null) {
            throw new ServiceException("语音留言不存在");
        }
        message.setStatus(request.getStatus());
        message.setHandleRemark(request.getHandleRemark());
        if ("UNHANDLED".equals(request.getStatus())) {
            message.setHandledBy(null);
            message.setHandledAt(null);
        } else {
            message.setHandledBy(LoginHelper.getUserId());
            message.setHandledAt(LocalDateTime.now());
        }
        mapper.updateById(message);
        log.info("处理语音留言，messageId={}，status={}", id, request.getStatus());
    }

    @Override
    public void upload(String tenantId, Long voicemailBoxId, String businessCallId, String callerNumber,
                       String calledNumber, Long durationMs, MultipartFile file) {
        if (StringUtils.isBlank(tenantId) || voicemailBoxId == null || StringUtils.isBlank(businessCallId) || file == null || file.isEmpty()) {
            throw new ServiceException("语音留言上传参数不完整");
        }
        TenantHelper.dynamic(tenantId, () -> {
            MediaAssetResponse media = mediaAssetService.storeVoicemail(businessCallId, durationMs, file);
            CallSession session = sessionMapper.selectOne(new LambdaQueryWrapper<CallSession>()
                .eq(CallSession::getBusinessCallId, businessCallId)
                .last("limit 1"));
            VoiceMailMessage message = new VoiceMailMessage();
            Date now = new Date();
            message.setCreateTime(now);
            message.setUpdateTime(now);
            message.setVoicemailBoxId(voicemailBoxId);
            message.setBusinessCallId(businessCallId);
            message.setCallerNumber(StringUtils.isNotBlank(callerNumber) ? callerNumber : session == null ? null : session.getCallerNumber());
            message.setCalledNumber(StringUtils.isNotBlank(calledNumber) ? calledNumber : session == null ? null : session.getCalledNumber());
            if (session != null) {
                message.setCallSessionId(session.getId());
                message.setNodeId(session.getNodeId());
                message.setCustomerId(session.getCustomerId());
                message.setTicketId(session.getTicketId());
            }
            applyBusinessFallback(message);
            message.setRecordingOssId(media.getOssId());
            message.setRecordingMediaId(media.getId());
            message.setRecordingFileName(file.getOriginalFilename());
            message.setDurationMs(durationMs != null ? durationMs : media.getDurationMs());
            message.setStatus("UNHANDLED");
            mapper.insert(message);
            linkRecordingToCallSession(session, message);
            appendTimelineEvent(message);
            log.info("语音留言已上传，tenantId={}，businessCallId={}，boxId={}，messageId={}，mediaId={}，ossId={}",
                tenantId, businessCallId, voicemailBoxId, message.getId(), media.getId(), media.getOssId());
        });
    }

    private void linkRecordingToCallSession(CallSession session, VoiceMailMessage message) {
        if (session == null || message.getRecordingOssId() == null) {
            return;
        }
        sessionMapper.update(null, new LambdaUpdateWrapper<CallSession>()
            .eq(CallSession::getId, session.getId())
            .isNull(CallSession::getRecordingOssId)
            .set(CallSession::getRecordingOssId, message.getRecordingOssId())
            .set(CallSession::getRecordingMediaId, message.getRecordingMediaId())
            .set(CallSession::getRecordingFileName, message.getRecordingFileName())
            .set(CallSession::getRecordingStatus, "UPLOADED"));
    }

    private void appendTimelineEvent(VoiceMailMessage message) {
        if (message.getCallSessionId() == null) {
            return;
        }
        CallEvent event = new CallEvent();
        event.setSessionId(message.getCallSessionId());
        event.setChannelUuid(message.getBusinessCallId());
        event.setEventType("VOICEMAIL_RECORDED");
        event.setFromTarget(message.getCallerNumber());
        event.setToTarget(String.valueOf(message.getVoicemailBoxId()));
        event.setOccurredAt(LocalDateTime.now());
        eventMapper.insert(event);
    }

    private void applyBusinessFallback(VoiceMailMessage message) {
        if (message.getCustomerId() == null && StringUtils.isNotBlank(message.getCallerNumber())) {
            try {
                message.setCustomerId(businessAssociationQueryService.findCustomerIdByPhone(message.getCallerNumber()));
            } catch (Exception exception) {
                log.warn("语音留言按主叫号码回查客户失败，callerNumber={}", message.getCallerNumber(), exception);
            }
        }
        if (message.getTicketId() == null && StringUtils.isNotBlank(message.getCallerNumber())) {
            try {
                message.setTicketId(businessAssociationQueryService.findLatestTicketIdByCallerNumber(message.getCallerNumber()));
            } catch (Exception exception) {
                log.warn("语音留言按主叫号码回查工单失败，callerNumber={}", message.getCallerNumber(), exception);
            }
        }
    }

    private VoiceMailMessageResponse toResponse(VoiceMailMessage message, boolean includePlaybackUrl) {
        VoiceMailMessageResponse response = new VoiceMailMessageResponse();
        response.setId(message.getId());
        response.setVoicemailBoxId(message.getVoicemailBoxId());
        response.setBusinessCallId(message.getBusinessCallId());
        response.setCallSessionId(message.getCallSessionId());
        response.setNodeId(message.getNodeId());
        response.setCallerNumber(message.getCallerNumber());
        response.setCalledNumber(message.getCalledNumber());
        response.setCustomerId(message.getCustomerId());
        response.setTicketId(message.getTicketId());
        response.setRecordingOssId(message.getRecordingOssId());
        response.setRecordingMediaId(message.getRecordingMediaId());
        response.setRecordingFileName(message.getRecordingFileName());
        response.setDurationMs(message.getDurationMs());
        response.setStatus(message.getStatus());
        response.setHandledBy(message.getHandledBy());
        response.setHandledAt(message.getHandledAt());
        response.setHandleRemark(message.getHandleRemark());
        response.setCreateTime(message.getCreateTime());
        response.setVersion(message.getVersion());
        if (includePlaybackUrl && message.getRecordingOssId() != null) {
            response.setPlaybackUrl(ossService.selectUrlById(message.getRecordingOssId(), PLAYBACK_URL_TTL));
        }
        return response;
    }
}
