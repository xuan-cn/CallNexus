package org.dromara.customer.ticket.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.customer.ticket.domain.request.CreateTicketRequest;
import org.dromara.customer.ticket.domain.request.TicketPageQuery;
import org.dromara.customer.ticket.domain.response.TicketResponse;
import org.dromara.customer.ticket.service.TicketApplicationService;
import org.dromara.customer.form.service.BusinessDataExportService;
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
    private static final int MAX_EXPORT_ROWS = 5000;
    private final TicketApplicationService applicationService;
    private final BusinessDataExportService exportService;

    @GetMapping
    public TableDataInfo<TicketResponse> page(TicketPageQuery query, PageQuery pageQuery) {
        return applicationService.page(query, pageQuery);
    }

    @PostMapping("/search")
    public TableDataInfo<TicketResponse> search(@RequestBody TicketPageQuery query) {
        return applicationService.page(query, new PageQuery(normalizePageSize(query.getPageSize()), normalizePageNum(query.getPageNum())));
    }

    @PostMapping("/export")
    public void export(@RequestBody TicketPageQuery query, jakarta.servlet.http.HttpServletResponse response) {
        TableDataInfo<TicketResponse> data = applicationService.page(query, new PageQuery(MAX_EXPORT_ROWS, 1));
        if (data.getTotal() > MAX_EXPORT_ROWS) {
            throw new org.dromara.common.core.exception.ServiceException("单次最多导出 " + MAX_EXPORT_ROWS + " 条工单，请缩小查询范围");
        }
        exportService.exportTickets(data.getRows(), query.getTemplateId(), response);
    }

    @GetMapping("/{id}")
    public R<TicketResponse> get(@PathVariable Long id) {
        return R.ok(applicationService.get(id));
    }

    @PostMapping
    @SaCheckPermission("callcenter:ticket:create")
    public R<Long> create(@Valid @RequestBody CreateTicketRequest request) {
        return R.ok(applicationService.create(request));
    }

    @PostMapping("/{id}/submit")
    public R<Void> submit(@PathVariable Long id) {
        applicationService.submit(id);
        return R.ok();
    }

    @PostMapping("/{id}/resolve")
    @SaCheckPermission("callcenter:ticket:create")
    public R<Void> resolveDirectly(@PathVariable Long id) {
        applicationService.resolveDirectly(id);
        return R.ok();
    }

    @PostMapping("/{id}/close")
    public R<Void> close(@PathVariable Long id) {
        applicationService.close(id);
        return R.ok();
    }

    private int normalizePageNum(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    private int normalizePageSize(Integer pageSize) {
        return pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, 200);
    }
}
