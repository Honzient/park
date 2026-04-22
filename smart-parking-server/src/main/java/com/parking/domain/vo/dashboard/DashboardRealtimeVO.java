package com.parking.domain.vo.dashboard;

import java.util.List;

public record DashboardRealtimeVO(
        DashboardCardVO cards,
        List<DashboardSpotVO> spots,
        List<DashboardRecentRecordVO> recentRecords,
        List<DashboardTrendPointVO> trend
) {
}
