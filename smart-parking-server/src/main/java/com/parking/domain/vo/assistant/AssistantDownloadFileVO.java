package com.parking.domain.vo.assistant;

public record AssistantDownloadFileVO(
        String fileName,
        String contentType,
        byte[] fileBytes
) {
}
