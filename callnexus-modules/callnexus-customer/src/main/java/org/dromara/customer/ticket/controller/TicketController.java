package org.dromara.customer.ticket.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.customer.ticket.domain.request.CreateTicketRequest;
import org.dromara.customer.ticket.domain.request.TicketPageQuery;
import org.dromara.customer.ticket.domain.response.TicketResponse;
import org.dromara.customer.ticket.service.TicketApplicationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SaCheckLogin
@RestController
@RequestMapping("/api/v1/tickets")
@RequiredArgsConstructor
public class TicketController {
    private final TicketApplicationService applicationService;

    @GetMapping
    public TableDataInfo<TicketResponse> page(TicketPageQuery query, PageQuery pageQuery) {
        return applicationService.page(query, pageQuery);
    }

    @GetMapping("/{id}")
    public R<TicketResponse> get(@PathVariable Long id) {
        return R.ok(applicationService.get(id));
    }

    @PostMapping
    public R<Long> create(@Valid @RequestBody CreateTicketRequest request) {
        return R.ok(applicationService.create(request));
    }
}
