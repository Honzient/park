package com.parking.domain.vo.assistant;

import java.util.Map;

public record AssistantPendingActionVO(
        String capabilityCode,
        String summary,
        Map<String, Object> params
) {
}
