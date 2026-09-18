package org.dromara.quality.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.quality.domain.request.QualitySamplingPlanQuery;
import org.dromara.quality.domain.request.QualitySamplingPlanRequest;
import org.dromara.quality.domain.response.QualityOptionResponse;
import org.dromara.quality.domain.response.QualitySamplingExecutionResponse;
import org.dromara.quality.domain.response.QualitySamplingPlanResponse;
import org.dromara.quality.service.QualitySamplingPlanService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/quality/plans")
@RequiredArgsConstructor
public class QualitySamplingPlanController {
    private final QualitySamplingPlanService service;

    @GetMapping
    @SaCheckPermission("callcenter:quality-plan:list")
    public TableDataInfo<QualitySamplingPlanResponse> page(QualitySamplingPlanQuery query, PageQuery pageQuery) {
        return service.page(query, pageQuery);
    }

    @GetMapping("/options")
    @SaCheckPermission("callcenter:quality-plan:list")
    public R<Map<String, List<QualityOptionResponse>>> options() {
        return R.ok(service.options());
    }

    @GetMapping("/{id}")
    @SaCheckPermission("callcenter:quality-plan:query")
    public R<QualitySamplingPlanResponse> get(@PathVariable Long id) {
        return R.ok(service.get(id));
    }

    @PostMapping
    @SaCheckPermission("callcenter:quality-plan:create")
    public R<Long> create(@Valid @RequestBody QualitySamplingPlanRequest request) {
        return R.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("callcenter:quality-plan:update")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody QualitySamplingPlanRequest request) {
        service.update(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("callcenter:quality-plan:delete")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }

    @PostMapping("/{id}/execute")
    @SaCheckPermission("callcenter:quality-plan:execute")
    public R<QualitySamplingExecutionResponse> execute(@PathVariable Long id) {
        return R.ok(service.execute(id, "MANUAL"));
    }

    @GetMapping("/{id}/executions")
    @SaCheckPermission("callcenter:quality-plan:query")
    public TableDataInfo<QualitySamplingExecutionResponse> executions(@PathVariable Long id, PageQuery pageQuery) {
        return service.executions(id, pageQuery);
    }
}
