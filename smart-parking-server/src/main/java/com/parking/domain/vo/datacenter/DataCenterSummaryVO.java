package com.parking.domain.vo.datacenter;

import java.math.BigDecimal;

public record DataCenterSummaryVO(
        long recordCount,
        long activeRecordCount,
        long exitedRecordCount,
        long entryEventCount,
        long exitEventCount,
        BigDecimal totalFee,
        long averageDurationMinutes
) {
}
