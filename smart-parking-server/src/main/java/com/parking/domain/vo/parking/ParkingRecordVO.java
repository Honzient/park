package com.parking.domain.vo.parking;

import java.math.BigDecimal;

public record ParkingRecordVO(
        Long id,
        String plateNumber,
        String parkNo,
        String entryTime,
        String exitTime,
        String duration,
        BigDecimal fee,
        String status,
        boolean notExited
) {
}
