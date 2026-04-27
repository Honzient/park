package com.parking.domain.dto.assistant;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class AssistantChatRequestDTO {

    @NotBlank
    private String message;

    private boolean confirm;

    private Map<String, Object> pendingAction;

    private List<AssistantConversationMessageDTO> history;
}
