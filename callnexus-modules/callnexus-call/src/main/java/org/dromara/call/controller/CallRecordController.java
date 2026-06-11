package org.dromara.call.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.call.domain.request.CallRecordPageQuery;
import org.dromara.call.domain.response.CallRecordResponse;
import org.dromara.call.service.CallRecordApplicationService;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/call-records")
@RequiredArgsConstructor
public class CallRecordController {
    private final CallRecordApplicationService applicationService;

    @GetMapping
    @SaCheckPermission("callcenter:call-record:list")
    public TableDataInfo<CallRecordResponse> page(CallRecordPageQuery query, PageQuery pageQuery) {
        return applicationService.page(query, pageQuery);
    }

    @GetMapping("/{id}")
    @SaCheckPermission("callcenter:call-record:query")
    public R<CallRecordResponse> get(@PathVariable Long id) {
        return R.ok(applicationService.get(id));
    }
}
