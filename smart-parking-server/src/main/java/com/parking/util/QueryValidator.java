package com.parking.util;

import com.parking.common.exception.BusinessException;

import java.time.LocalDateTime;

public final class QueryValidator {

    private QueryValidator() {
    }

    public static void validateTimeRange(LocalDateTime startTime, LocalDateTime endTime) {
        if (startTime != null && endTime != null && startTime.isAfter(endTime)) {
            throw new BusinessException(400, "Start time cannot be later than end time");
        }
    }
}
