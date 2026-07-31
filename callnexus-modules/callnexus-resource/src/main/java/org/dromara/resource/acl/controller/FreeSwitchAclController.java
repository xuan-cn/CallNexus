package org.dromara.resource.acl.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.resource.acl.domain.request.FreeSwitchAclIpTestRequest;
import org.dromara.resource.acl.domain.request.FreeSwitchAclPageQuery;
import org.dromara.resource.acl.domain.request.FreeSwitchAclSaveRequest;
import org.dromara.resource.acl.domain.response.FreeSwitchAclIpTestResponse;
import org.dromara.resource.acl.domain.response.FreeSwitchAclResponse;
import org.dromara.resource.acl.service.FreeSwitchAclApplicationService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/freeswitch-acls")
@RequiredArgsConstructor
public class FreeSwitchAclController {
    private final FreeSwitchAclApplicationService applicationService;

    @GetMapping
    @SaCheckPermission("callcenter:freeswitch-acl:list")
    public TableDataInfo<FreeSwitchAclResponse> page(FreeSwitchAclPageQuery query, PageQuery pageQuery) {
        return applicationService.page(query, pageQuery);
    }

    @GetMapping("/{id}")
    @SaCheckPermission("callcenter:freeswitch-acl:query")
    public R<FreeSwitchAclResponse> get(@PathVariable Long id) {
        return R.ok(applicationService.get(id));
    }

    @PostMapping
    @SaCheckPermission("callcenter:freeswitch-acl:create")
    public R<Long> create(@Valid @RequestBody FreeSwitchAclSaveRequest request) {
        return R.ok(applicationService.create(request));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("callcenter:freeswitch-acl:update")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody FreeSwitchAclSaveRequest request) {
        applicationService.update(id, request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("callcenter:freeswitch-acl:delete")
    public R<Void> delete(@PathVariable Long id) {
        applicationService.delete(id);
        return R.ok();
    }

    @PostMapping("/{id}/publish")
    @SaCheckPermission("callcenter:freeswitch-acl:publish")
    public R<Void> publish(@PathVariable Long id) {
        applicationService.publish(id);
        return R.ok();
    }

    @PostMapping("/{id}/rollback")
    @SaCheckPermission("callcenter:freeswitch-acl:publish")
    public R<Void> rollback(@PathVariable Long id) {
        applicationService.rollback(id);
        return R.ok();
    }

    @PostMapping("/{id}/test-ip")
    @SaCheckPermission("callcenter:freeswitch-acl:query")
    public R<FreeSwitchAclIpTestResponse> testIp(@PathVariable Long id,
                                                 @Valid @RequestBody FreeSwitchAclIpTestRequest request) {
        return R.ok(applicationService.testIp(id, request.getIp()));
    }

    @GetMapping("/{id}/preview")
    @SaCheckPermission("callcenter:freeswitch-acl:query")
    public R<String> preview(@PathVariable Long id) {
        return R.ok("查询成功", applicationService.preview(id));
    }
}
