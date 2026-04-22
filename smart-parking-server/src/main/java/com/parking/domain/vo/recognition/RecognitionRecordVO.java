package com.parking.domain.vo.recognition;

import java.math.BigDecimal;

public record RecognitionRecordVO(
        Long id,
        String plateNumber,
        String recognitionTime,
        BigDecimal accuracy,
        String recognitionType,
        String sourceUrl
) {
}
