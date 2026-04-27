package com.parking.controller;

import com.parking.common.ApiResponse;
import com.parking.domain.dto.assistant.AssistantChatRequestDTO;
import com.parking.domain.vo.assistant.AssistantCapabilityVO;
import com.parking.domain.vo.assistant.AssistantChatResponseVO;
import com.parking.service.AssistantService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/assistant")
@PreAuthorize("isAuthenticated()")
public class AssistantController {

    private final AssistantService assistantService;

    public AssistantController(AssistantService assistantService) {
        this.assistantService = assistantService;
    }

    @GetMapping("/capabilities")
    public ApiResponse<List<AssistantCapabilityVO>> capabilities() {
        return ApiResponse.success(assistantService.listCapabilities());
    }

    @PostMapping("/chat")
    public ApiResponse<AssistantChatResponseVO> chat(@Valid @RequestBody AssistantChatRequestDTO request) {
        return ApiResponse.success(assistantService.chat(request));
    }
}
