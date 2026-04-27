package com.parking.domain.vo.assistant;

public record AssistantCapabilityVO(
        String code,
        String name,
        String description,
        boolean readOnly,
        boolean confirmationRequired
) {
}
