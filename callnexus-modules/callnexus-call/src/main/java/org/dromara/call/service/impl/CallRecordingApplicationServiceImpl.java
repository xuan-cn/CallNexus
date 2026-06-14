package org.dromara.call.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
            throw new ServiceException("通话录音上传参数不合法");
        }
        TenantHelper.dynamic(tenantId, () -> {
            CallSession session = sessionMapper.selectOne(new LambdaQueryWrapper<CallSession>()
                .eq(CallSession::getBusinessCallId, businessCallId)
                .last("limit 1"));
            if (session == null) throw new ServiceException("通话记录不存在");
            try {
                updateRecordingStatus(businessCallId, "PENDING");
                MediaAssetResponse media = mediaAssetService.storeRecording(businessCallId, recordingDurationMs(session), file);
                int updated = sessionMapper.update(null, new LambdaUpdateWrapper<CallSession>()
                    .eq(CallSession::getBusinessCallId, businessCallId)
                    .set(CallSession::getRecordingOssId, media.getOssId())
                    .set(CallSession::getRecordingMediaId, media.getId())
                    .set(CallSession::getRecordingFileName, file.getOriginalFilename())
                    .set(CallSession::getRecordingStatus, "UPLOADED"));
                if (updated != 1) {
                    throw new ServiceException("录音文件已上传，但关联通话记录失败");
                }
                log.info("通话录音已上传并关联业务通话，tenantId={}，businessCallId={}，mediaId={}，ossId={}",
                    tenantId, businessCallId, media.getId(), media.getOssId());
            } catch (RuntimeException exception) {
                updateRecordingStatus(businessCallId, "FAILED");
                throw exception;
            }
        });
    }

    private void updateRecordingStatus(String businessCallId, String status) {
        sessionMapper.update(null, new LambdaUpdateWrapper<CallSession>()
            .eq(CallSession::getBusinessCallId, businessCallId)
            .ne(CallSession::getRecordingStatus, "UPLOADED")
            .set(CallSession::getRecordingStatus, status));
    }

    private Long recordingDurationMs(CallSession session) {
        Integer seconds = session.getBillableSeconds() != null && session.getBillableSeconds() > 0
            ? session.getBillableSeconds()
            : session.getDurationSeconds();
        return seconds == null || seconds <= 0 ? null : seconds.longValue() * 1000;
    }
}
