package org.dromara.call.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.call.domain.CallSession;
import org.dromara.call.mapper.CallSessionMapper;
import org.dromara.call.service.CallRecordingApplicationService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.media.domain.response.MediaAssetResponse;
import org.dromara.resource.media.service.MediaAssetApplicationService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Slf4j
public class CallRecordingApplicationServiceImpl implements CallRecordingApplicationService {
    private final CallSessionMapper sessionMapper;
    private final MediaAssetApplicationService mediaAssetService;

    @Override
    public void upload(String tenantId, String businessCallId, MultipartFile file) {
        if (StringUtils.isBlank(tenantId) || StringUtils.isBlank(businessCallId) || file == null || file.isEmpty()) {
            throw new ServiceException("CALL_RECORDING_REQUEST_INVALID");
        }
        TenantHelper.dynamic(tenantId, () -> {
            CallSession session = sessionMapper.selectOne(new LambdaQueryWrapper<CallSession>()
                .eq(CallSession::getBusinessCallId, businessCallId)
                .last("limit 1"));
            if (session == null) throw new ServiceException("CALL_RECORD_NOT_FOUND");
            try {
                session.setRecordingStatus("PENDING");
                sessionMapper.updateById(session);
                MediaAssetResponse media = mediaAssetService.storeRecording(businessCallId, recordingDurationMs(session), file);
                session.setRecordingOssId(media.getOssId());
                session.setRecordingMediaId(media.getId());
                session.setRecordingFileName(file.getOriginalFilename());
                session.setRecordingStatus("UPLOADED");
                sessionMapper.updateById(session);
                log.info("通话录音已上传并关联业务通话，tenantId={}，businessCallId={}，mediaId={}，ossId={}",
                    tenantId, businessCallId, media.getId(), media.getOssId());
            } catch (RuntimeException exception) {
                session.setRecordingStatus("FAILED");
                sessionMapper.updateById(session);
                throw exception;
            }
        });
    }

    private Long recordingDurationMs(CallSession session) {
        Integer seconds = session.getBillableSeconds() != null && session.getBillableSeconds() > 0
            ? session.getBillableSeconds()
            : session.getDurationSeconds();
        return seconds == null || seconds <= 0 ? null : seconds.longValue() * 1000;
    }
}
