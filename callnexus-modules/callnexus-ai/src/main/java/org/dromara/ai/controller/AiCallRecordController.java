package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.request.AiCallRecordQuery;
import org.dromara.ai.domain.response.AiCallRecordResponse;
import org.dromara.ai.service.AiCallRecordApplicationService;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai/call-records")
@RequiredArgsConstructor
public class AiCallRecordController {

    private final AiCallRecordApplicationService service;

    @GetMapping
    @SaCheckPermission("callcenter:ai-call-record:list")
    public TableDataInfo<AiCallRecordResponse> page(AiCallRecordQuery query) {
        return service.page(query);
    }
}
