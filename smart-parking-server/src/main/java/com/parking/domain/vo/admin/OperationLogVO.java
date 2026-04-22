package com.parking.domain.vo.admin;

public record OperationLogVO(
        Long id,
        String operatorName,
        String operationContent,
        String operationTime,
        String ip,
        String device
) {
}
