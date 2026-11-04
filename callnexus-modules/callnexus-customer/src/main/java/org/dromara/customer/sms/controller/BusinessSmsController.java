package org.dromara.customer.sms.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.customer.sms.domain.SmsRequests;
import org.dromara.customer.sms.service.BusinessSmsService;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/sms")
@RequiredArgsConstructor
public class BusinessSmsController {
    private final BusinessSmsService service;

    @GetMapping("/channels") @SaCheckPermission("callcenter:sms-channel:list")
    public R<List<BusinessSmsService.ChannelView>> channels() { return R.ok(service.channels()); }
    @PostMapping("/channels") @SaCheckPermission("callcenter:sms-channel:manage")
    public R<Long> createChannel(@Valid @RequestBody SmsRequests.Channel request) { return R.ok(service.saveChannel(null, request)); }
    @PutMapping("/channels/{id}") @SaCheckPermission("callcenter:sms-channel:manage")
    public R<Long> updateChannel(@PathVariable Long id, @Valid @RequestBody SmsRequests.Channel request) { return R.ok(service.saveChannel(id, request)); }
    @GetMapping("/template-channels")
    @SaCheckPermission(value={"callcenter:sms-template:list", "callcenter:sms-template:manage"}, mode=SaMode.OR)
    public R<List<BusinessSmsService.ChannelView>> templateChannels() { return R.ok(service.channels()); }
    @GetMapping("/templates") @SaCheckPermission("callcenter:sms-template:list")
    public R<List<BusinessSmsService.TemplateView>> templates() { return R.ok(service.templates(false)); }
    @PostMapping("/templates") @SaCheckPermission("callcenter:sms-template:manage")
    public R<Long> createTemplate(@Valid @RequestBody SmsRequests.Template request) { return R.ok(service.saveTemplate(null, request)); }
    @PutMapping("/templates/{id}") @SaCheckPermission("callcenter:sms-template:manage")
    public R<Long> updateTemplate(@PathVariable Long id, @Valid @RequestBody SmsRequests.Template request) { return R.ok(service.saveTemplate(id, request)); }
    @GetMapping("/channels/{id}/test-templates") @SaCheckPermission("callcenter:sms-channel:test")
    public R<List<BusinessSmsService.TemplateView>> testTemplates(@PathVariable Long id) {
        service.channel(id);
        return R.ok(service.templates(true).stream().filter(template -> template.channelId().equals(id)).toList());
    }
    @PostMapping("/channels/{id}/test") @SaCheckPermission("callcenter:sms-channel:test")
    public R<Long> test(@PathVariable Long id, @Valid @RequestBody SmsRequests.Send request) { return R.ok(service.enqueue(null, id, request)); }
    @PostMapping("/channels/{id}/test-preview") @SaCheckPermission("callcenter:sms-channel:test")
    public R<BusinessSmsService.Preview> testPreview(@PathVariable Long id, @Valid @RequestBody SmsRequests.Send request) { return R.ok(service.testPreview(id, request)); }
    @GetMapping("/records") @SaCheckPermission("callcenter:sms-record:list")
    public TableDataInfo<BusinessSmsService.RecordView> records(String phone, String status, PageQuery pageQuery) {
        return service.records(null, phone, status, pageQuery);
    }
    @GetMapping("/customers/{id}") @SaCheckPermission("callcenter:customer:sms")
    public R<Map<String,Object>> context(@PathVariable Long id) { return R.ok(service.customerContext(id)); }
    @PostMapping("/customers/{id}/preview") @SaCheckPermission("callcenter:customer:sms")
    public R<BusinessSmsService.Preview> preview(@PathVariable Long id, @Valid @RequestBody SmsRequests.Send request) { return R.ok(service.preview(id, request)); }
    @PostMapping("/customers/{id}/send") @SaCheckPermission("callcenter:customer:sms")
    public R<Long> send(@PathVariable Long id, @Valid @RequestBody SmsRequests.Send request) { return R.ok(service.enqueue(id, null, request)); }
    @GetMapping("/customers/{id}/records") @SaCheckPermission("callcenter:customer:sms")
    public TableDataInfo<BusinessSmsService.RecordView> customerRecords(@PathVariable Long id, PageQuery pageQuery) {
        return service.records(id, null, null, pageQuery);
    }
}
