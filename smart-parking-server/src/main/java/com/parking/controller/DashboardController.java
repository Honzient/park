package com.parking.controller;

import com.parking.annotation.QueryLog;
import com.parking.common.ApiResponse;
import com.parking.domain.vo.dashboard.DashboardRealtimeVO;
import com.parking.service.DashboardService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/realtime")
    @PreAuthorize("hasAuthority('dashboard:view')")
    @QueryLog(module = "DASHBOARD_REALTIME")
    public ApiResponse<DashboardRealtimeVO> realtime(@RequestParam(defaultValue = "TODAY") String range) {
        return ApiResponse.success(dashboardService.realtime(range));
    }
}
