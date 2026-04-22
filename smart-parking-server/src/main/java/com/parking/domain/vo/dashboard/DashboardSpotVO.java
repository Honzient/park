package com.parking.domain.vo.dashboard;

public record DashboardSpotVO(
        String spotNo,
        String status,
        String plateNumber,
        String entryTime
) {
}
