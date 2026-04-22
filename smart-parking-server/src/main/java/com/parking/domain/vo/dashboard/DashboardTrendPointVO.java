package com.parking.domain.vo.dashboard;

import java.math.BigDecimal;

public record DashboardTrendPointVO(
        String label,
        long traffic,
        BigDecimal income
) {
}
