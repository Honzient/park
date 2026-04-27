package com.parking.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "parking.assistant.llm")
public class AssistantLlmProperties {

    private boolean enabled = true;

    private String baseUrl = "https://api.deepseek.com/beta";

    private String apiKey = "";

    private String model = "deepseek-v4-flash";

    private int timeoutMs = 15000;

    private int maxHistoryMessages = 8;
}
