package com.parking.websocket;

import com.parking.common.ApiResponse;
import com.parking.service.DashboardService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DashboardPushScheduler {

    private final DashboardService dashboardService;
    private final DashboardWebSocketHandler dashboardWebSocketHandler;

    public DashboardPushScheduler(DashboardService dashboardService, DashboardWebSocketHandler dashboardWebSocketHandler) {
        this.dashboardService = dashboardService;
        this.dashboardWebSocketHandler = dashboardWebSocketHandler;
    }

    @Scheduled(fixedDelay = 5000)
    public void pushRealtimeData() {
        dashboardWebSocketHandler.broadcast(ApiResponse.success(dashboardService.realtime("TODAY")));
    }
}
