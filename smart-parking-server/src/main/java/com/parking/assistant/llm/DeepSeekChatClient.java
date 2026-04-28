package com.parking.assistant.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parking.config.AssistantLlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class DeepSeekChatClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekChatClient.class);
    private static final String TOOL_CALLING_MODEL = "deepseek-v4-flash";

    private final AssistantLlmProperties properties;
    private final ObjectMapper objectMapper;

    public DeepSeekChatClient(AssistantLlmProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public boolean isAvailable() {
        return properties.isEnabled() && StringUtils.hasText(properties.getApiKey());
    }

    public Optional<String> callTool(List<Map<String, Object>> messages,
                                     List<Map<String, Object>> tools,
                                     String toolName) {
        if (!isAvailable()) {
            return Optional.empty();
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", TOOL_CALLING_MODEL);
            payload.put("messages", messages);
            payload.put("tools", tools);
            payload.put("parallel_tool_calls", false);
            payload.put("tool_choice", Map.of(
                    "type", "function",
                    "function", Map.of("name", toolName)
            ));
            payload.put("temperature", 0.1);
            payload.put("stream", false);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            headers.setBearerAuth(properties.getApiKey().trim());

            ResponseEntity<String> response = buildRestTemplate().postForEntity(
                    buildChatUrl(),
                    new HttpEntity<>(payload, headers),
                    String.class
            );

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode toolCalls = root.path("choices").path(0).path("message").path("tool_calls");
            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                return Optional.empty();
            }

            String arguments = toolCalls.path(0).path("function").path("arguments").asText(null);
            return StringUtils.hasText(arguments) ? Optional.of(arguments) : Optional.empty();
        } catch (RestClientException exception) {
            log.warn("DeepSeek chat request failed: {}", exception.getMessage());
            return Optional.empty();
        } catch (Exception exception) {
            log.warn("DeepSeek chat response parse failed: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    public Optional<String> callJson(List<Map<String, Object>> messages) {
        if (!isAvailable()) {
            return Optional.empty();
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", StringUtils.hasText(properties.getModel())
                    ? properties.getModel().trim()
                    : TOOL_CALLING_MODEL);
            payload.put("messages", messages);
            payload.put("response_format", Map.of("type", "json_object"));
            payload.put("temperature", 0.1);
            payload.put("stream", false);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            headers.setBearerAuth(properties.getApiKey().trim());

            ResponseEntity<String> response = buildRestTemplate().postForEntity(
                    buildChatUrl(),
                    new HttpEntity<>(payload, headers),
                    String.class
            );

            JsonNode root = objectMapper.readTree(response.getBody());
            String content = root.path("choices").path(0).path("message").path("content").asText(null);
            return StringUtils.hasText(content) ? Optional.of(content.trim()) : Optional.empty();
        } catch (RestClientException exception) {
            log.warn("DeepSeek json request failed: {}", exception.getMessage());
            return Optional.empty();
        } catch (Exception exception) {
            log.warn("DeepSeek json response parse failed: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    public Optional<String> callText(List<Map<String, Object>> messages) {
        if (!isAvailable()) {
            return Optional.empty();
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", StringUtils.hasText(properties.getModel())
                    ? properties.getModel().trim()
                    : TOOL_CALLING_MODEL);
            payload.put("messages", messages);
            payload.put("temperature", 0.3);
            payload.put("stream", false);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            headers.setBearerAuth(properties.getApiKey().trim());

            ResponseEntity<String> response = buildRestTemplate().postForEntity(
                    buildChatUrl(),
                    new HttpEntity<>(payload, headers),
                    String.class
            );

            JsonNode root = objectMapper.readTree(response.getBody());
            String content = root.path("choices").path(0).path("message").path("content").asText(null);
            return StringUtils.hasText(content) ? Optional.of(content.trim()) : Optional.empty();
        } catch (RestClientException exception) {
            log.warn("DeepSeek text request failed: {}", exception.getMessage());
            return Optional.empty();
        } catch (Exception exception) {
            log.warn("DeepSeek text response parse failed: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    private RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getTimeoutMs());
        requestFactory.setReadTimeout(properties.getTimeoutMs());
        return new RestTemplate(requestFactory);
    }

    private String buildChatUrl() {
        String baseUrl = properties.getBaseUrl().trim();
        if (baseUrl.endsWith("/")) {
            return baseUrl + "chat/completions";
        }
        return baseUrl + "/chat/completions";
    }
}
