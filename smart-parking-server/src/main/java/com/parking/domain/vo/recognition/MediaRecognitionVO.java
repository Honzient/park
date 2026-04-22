package com.parking.domain.vo.recognition;

public record MediaRecognitionVO(
        String recognitionType,
        String plateNumber,
        double accuracy,
        String source,
        String cameraAccessGuide
) {
}
