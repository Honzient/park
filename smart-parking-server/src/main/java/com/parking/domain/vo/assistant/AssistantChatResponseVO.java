package com.parking.domain.vo.assistant;

import java.util.List;

public record AssistantChatResponseVO(
        String message,
        String matchedCapabilityCode,
        boolean requiresConfirmation,
        AssistantPendingActionVO pendingAction,
        Object data,
        List<String> suggestions,
        List<AssistantCapabilityVO> capabilities
) {
}
