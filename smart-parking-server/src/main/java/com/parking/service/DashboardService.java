package com.parking.service;

import com.parking.domain.vo.dashboard.DashboardRealtimeVO;

public interface DashboardService {

    DashboardRealtimeVO realtime(String range);
}
