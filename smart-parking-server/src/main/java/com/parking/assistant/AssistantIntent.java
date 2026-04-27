package com.parking.assistant;

import java.util.Map;

public record AssistantIntent(
        AssistantCapability capability,
        Map<String, Object> params
) {
}
