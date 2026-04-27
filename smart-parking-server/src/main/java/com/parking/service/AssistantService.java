package com.parking.service;

import com.parking.domain.dto.assistant.AssistantChatRequestDTO;
import com.parking.domain.vo.assistant.AssistantCapabilityVO;
import com.parking.domain.vo.assistant.AssistantChatResponseVO;

import java.util.List;

public interface AssistantService {

    List<AssistantCapabilityVO> listCapabilities();

    AssistantChatResponseVO chat(AssistantChatRequestDTO request);
}
