package com.parking.domain.vo.dashboard;

import java.math.BigDecimal;

public record DashboardCardVO(
        long totalSpots,
        long occupiedSpots,
        long freeSpots,
        BigDecimal todayIncome,
        double incomeTrendPercent
) {
}
