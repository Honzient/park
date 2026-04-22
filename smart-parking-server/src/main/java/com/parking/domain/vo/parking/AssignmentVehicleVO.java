package com.parking.domain.vo.parking;

public record AssignmentVehicleVO(
        Long recordId,
        String plateNumber,
        String currentSpotNo,
        String entryTime
) {
}
