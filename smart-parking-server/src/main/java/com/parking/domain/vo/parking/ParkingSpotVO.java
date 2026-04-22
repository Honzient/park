package com.parking.domain.vo.parking;

public record ParkingSpotVO(
        String spotNo,
        String status,
        String plateNumber,
        String entryTime
) {
}
