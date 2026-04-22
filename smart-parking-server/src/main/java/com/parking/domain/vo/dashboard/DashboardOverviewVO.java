package com.parking.domain.vo.dashboard;

import java.util.List;

public record DashboardOverviewVO(
        long totalSpace,
        long occupiedSpace,
        long todayEntries,
        long todayExits,
        long todayRecognitionCount,
        double occupancyRate,
        double averageAccuracy,
        List<TrendPointVO> trends
) {
}
