package com.parking.domain.vo.dashboard;

public record DashboardRecentRecordVO(
        String plateNumber,
        String entryTime,
        String exitTime,
        String status
) {
}
