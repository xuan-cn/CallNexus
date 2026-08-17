package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.request.AiCallRecordQuery;
import org.dromara.ai.domain.response.AiCallRecordResponse;
import org.dromara.ai.mapper.AiCallRecordQueryMapper;
import org.dromara.ai.service.AiCallRecordApplicationService;
import org.dromara.common.core.service.OssService;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class AiCallRecordApplicationServiceImpl implements AiCallRecordApplicationService {

    private static final Duration RECORDING_PRESIGNED_TTL = Duration.ofHours(2);

    private final AiCallRecordQueryMapper mapper;
    private final OssService ossService;

    @Override
    public TableDataInfo<AiCallRecordResponse> page(AiCallRecordQuery query) {
        Page<AiCallRecordResponse> page = mapper.page(new PageQuery(query.getPageSize(), query.getPageNum()).build(), TenantHelper.getTenantId(), query);
        page.getRecords().forEach(record -> {
            if (record.getRecordingOssId() != null) {
                record.setRecordingUrl(ossService.selectUrlById(record.getRecordingOssId(), RECORDING_PRESIGNED_TTL));
            }
        });
        return TableDataInfo.build(page);
    }
}
