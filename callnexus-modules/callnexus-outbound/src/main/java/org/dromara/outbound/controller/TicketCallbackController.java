package org.dromara.outbound.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.outbound.service.impl.TicketCallbackService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@SaCheckLogin
@RestController
@RequestMapping("/api/v1/tickets/{id}/callbacks")
@RequiredArgsConstructor
public class TicketCallbackController {
    private final TicketCallbackService service;

    @GetMapping
    public R<List<TicketCallbackService.History>> history(@PathVariable Long id) {
        return R.ok(service.history(id));
    }

    @GetMapping("/options")
    @SaCheckPermission("callcenter:auto-outbound-task:execute")
    public R<TicketCallbackService.Options> options(@PathVariable Long id) {
        return R.ok(service.options(id));
    }

    @GetMapping("/mode")
    public R<TicketCallbackService.CallbackMode> mode(@PathVariable Long id) {
        return R.ok(service.mode(id));
    }

    @PutMapping("/contact")
    @SaCheckPermission("callcenter:auto-outbound-task:execute")
    public R<Void> confirmContact(@PathVariable Long id, @Valid @RequestBody TicketCallbackService.ContactRequest request) {
        service.confirmContact(id, request);
        return R.ok();
    }

    @PostMapping
    @SaCheckPermission("callcenter:auto-outbound-task:execute")
    public R<Long> enqueue(@PathVariable Long id, @Valid @RequestBody TicketCallbackService.Request request) {
        return R.ok(service.enqueue(id, request));
    }
}
