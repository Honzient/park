package com.parking.domain.dto.assistant;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AssistantConversationMessageDTO {

    @NotBlank
    private String role;

    @NotBlank
    private String content;
}
