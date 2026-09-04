package org.dromara.outbound.home;

import cn.dev33.satoken.annotation.SaCheckLogin;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统首页业务概览：客户、工单、呼入呼出。
 */
@SaCheckLogin
@RestController
@RequestMapping("/api/v1/callcenter/home")
@RequiredArgsConstructor
public class HomeBusinessOverviewController {

    private final HomeBusinessOverviewService overviewService;

    @GetMapping("/overview")
    public R<HomeBusinessOverviewResponse> overview(
        @RequestParam(required = false) String beginDate,
        @RequestParam(required = false) String endDate) {
        return R.ok(overviewService.overview(beginDate, endDate));
    }
}
