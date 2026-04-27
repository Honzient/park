package com.parking.assistant.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parking.assistant.AssistantCapability;
import com.parking.assistant.AssistantIntent;
import com.parking.config.AssistantLlmProperties;
import com.parking.domain.dto.assistant.AssistantChatRequestDTO;
import com.parking.domain.dto.assistant.AssistantConversationMessageDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AssistantLlmParser {

    private static final Logger log = LoggerFactory.getLogger(AssistantLlmParser.class);
    private static final String TOOL_NAME = "route_assistant_action";
    private static final String NO_MATCH = "NO_MATCH";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AssistantLlmProperties properties;
    private final DeepSeekChatClient deepSeekChatClient;
    private final ObjectMapper objectMapper;

    public AssistantLlmParser(AssistantLlmProperties properties,
                              DeepSeekChatClient deepSeekChatClient,
                              ObjectMapper objectMapper) {
        this.properties = properties;
        this.deepSeekChatClient = deepSeekChatClient;
        this.objectMapper = objectMapper;
    }

    public AssistantIntent parse(AssistantChatRequestDTO request, Set<AssistantCapability> availableCapabilities) {
        if (request == null || !StringUtils.hasText(request.getMessage()) || availableCapabilities.isEmpty()) {
            return null;
        }
        if (!deepSeekChatClient.isAvailable()) {
            return null;
        }

        try {
            OptionalRoute route = parseRoute(request, availableCapabilities);
            if (route == null || !StringUtils.hasText(route.capabilityCode())
                    || NO_MATCH.equalsIgnoreCase(route.capabilityCode())) {
                return null;
            }

            AssistantCapability capability = AssistantCapability.fromCode(route.capabilityCode()).orElse(null);
            if (capability == null) {
                return null;
            }

            Map<String, Object> params = new LinkedHashMap<>();
            putIfHasText(params, "range", route.range());
            putIfHasText(params, "rangePreset", route.rangePreset());
            putIfHasText(params, "lastDays", route.lastDays());
            putIfHasText(params, "plateNumber", route.plateNumber());
            putIfHasText(params, "parkNo", route.parkNo());
            putIfHasText(params, "startTime", route.startTime());
            putIfHasText(params, "endTime", route.endTime());
            putIfHasText(params, "status", route.status());
            putIfHasText(params, "recognitionType", route.recognitionType());
            putIfHasText(params, "minAccuracy", route.minAccuracy());
            putIfHasText(params, "recordId", route.recordId());
            putIfHasText(params, "fee", route.fee());
            putIfHasText(params, "exitTime", route.exitTime());
            putIfHasText(params, "targetSpotNo", route.targetSpotNo());
            putIfHasText(params, "targetStatus", route.targetStatus());
            putIfHasText(params, "keyword", route.keyword());
            putIfHasText(params, "userHint", route.userHint());
            putIfHasText(params, "roleHint", route.roleHint());
            putIfHasText(params, "username", route.username());
            putIfHasText(params, "realName", route.realName());
            putIfHasText(params, "phone", route.phone());
            putIfHasText(params, "permissionOperation", route.permissionOperation());
            putIfHasText(params, "oldPassword", route.oldPassword());
            putIfHasText(params, "newPassword", route.newPassword());
            putListIfPresent(params, "statuses", route.statuses());
            putListIfPresent(params, "permissions", route.permissions());
            if (capability == AssistantCapability.ADMIN_ROLE_PERMISSION_UPDATE) {
                params.put("permissionsText", route.permissionsText() == null ? "" : route.permissionsText().trim());
            } else {
                putIfHasText(params, "permissionsText", route.permissionsText());
            }

            return new AssistantIntent(capability, params);
        } catch (Exception exception) {
            log.warn("Assistant LLM parse failed: {}", exception.getMessage());
            return null;
        }
    }

    private OptionalRoute parseRoute(AssistantChatRequestDTO request,
                                     Set<AssistantCapability> availableCapabilities) throws Exception {
        List<Map<String, Object>> messages = buildMessages(request, availableCapabilities);
        List<Map<String, Object>> tools = List.of(buildRouteTool(availableCapabilities));
        OptionalRoute toolRoute = deepSeekChatClient.callTool(messages, tools, TOOL_NAME)
                .map(this::readRoute)
                .orElse(null);
        if (toolRoute != null) {
            return toolRoute;
        }
        return deepSeekChatClient.callJson(messages)
                .map(this::extractJsonContent)
                .map(this::readRoute)
                .orElse(null);
    }

    private OptionalRoute readRoute(String arguments) {
        try {
            return objectMapper.readValue(arguments, OptionalRoute.class);
        } catch (Exception exception) {
            log.warn("Assistant LLM arguments parse failed: {}", exception.getMessage());
            return null;
        }
    }

    private List<Map<String, Object>> buildMessages(AssistantChatRequestDTO request,
                                                    Set<AssistantCapability> availableCapabilities) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of(
                "role", "system",
                "content", buildSystemPrompt(availableCapabilities)
        ));

        List<AssistantConversationMessageDTO> history = request.getHistory() == null
                ? List.of()
                : request.getHistory();
        int fromIndex = Math.max(history.size() - Math.max(properties.getMaxHistoryMessages(), 0), 0);
        for (AssistantConversationMessageDTO item : history.subList(fromIndex, history.size())) {
            String role = normalizeRole(item == null ? null : item.getRole());
            String content = item == null ? null : item.getContent();
            if (!StringUtils.hasText(role) || !StringUtils.hasText(content)) {
                continue;
            }
            messages.add(Map.of("role", role, "content", content.trim()));
        }

        messages.add(Map.of("role", "user", "content", request.getMessage().trim()));
        return messages;
    }

    private String buildSystemPrompt(Set<AssistantCapability> availableCapabilities) {
        LocalDateTime now = LocalDateTime.now();
        StringBuilder builder = new StringBuilder();
        builder.append("You convert the latest user message into exactly one structured assistant action for a Chinese smart parking system.\n");
        builder.append("If function calling is available, return exactly one tool call and never answer the user directly.\n");
        builder.append("If function calling is unavailable, return exactly one compact JSON object and never answer in prose.\n");
        builder.append("If the request cannot be mapped to the allowed capabilities, set capabilityCode to NO_MATCH and leave every other field empty.\n");
        builder.append("Only choose from these allowed capabilities:\n");
        availableCapabilities.stream()
                .sorted(Comparator.comparing(AssistantCapability::code))
                .forEach(capability -> builder.append("- ")
                        .append(capability.code())
                        .append(": ")
                        .append(capability.displayName())
                        .append(". ")
                        .append(capability.description())
                        .append(". confirmationRequired=")
                        .append(capability.confirmationRequired())
                        .append('\n'));
        builder.append("Current local time is ")
                .append(now.format(DATE_TIME_FORMATTER))
                .append(" in timezone Asia/Shanghai.\n");
        builder.append("Date rules:\n");
        builder.append("- Use yyyy-MM-dd HH:mm:ss for startTime and endTime.\n");
        builder.append("- If the user mentions only a date, use 00:00:00 for startTime and 23:59:59 for endTime.\n");
        builder.append("- If the user omits the year in a date like 4.16, infer year ")
                .append(LocalDate.now().getYear())
                .append(".\n");
        builder.append("- For open-ended ranges like 'after 4.16', fill startTime and leave endTime empty.\n");
        builder.append("- For open-ended ranges like 'before 4.16', fill endTime and leave startTime empty.\n");
        builder.append("- For phrases like 'recent 10 days', fill lastDays with 10 instead of approximating another preset.\n");
        builder.append("Field rules:\n");
        builder.append("- Keep fuzzy plate prefixes such as JingA or 京A in plateNumber.\n");
        builder.append("- Parking and data center record status should prefer Chinese labels like 未出场 or 已出场 when the user means entry/exit status.\n");
        builder.append("- Parking spot status must be FREE, OCCUPIED, MAINTENANCE or RESERVED.\n");
        builder.append("- Recognition type must be IMAGE or VIDEO.\n");
        builder.append("- For role permission changes, use roleHint for the role name or code.\n");
        builder.append("- For role permission changes, use permissionOperation=ADD, REMOVE, REPLACE, CLEAR or ALL.\n");
        builder.append("- Put exact permission codes into permissions only when you are confident. Otherwise keep permissions empty and put the requested permission phrase into permissionsText.\n");
        builder.append("- Use userHint and roleHint to preserve the user's original wording for usernames, real names, role names or role codes.\n");
        builder.append("JSON fallback rules:\n");
        builder.append("- Return one JSON object only, without markdown fences, explanations or extra text.\n");
        builder.append("- Include every key exactly once: capabilityCode, range, rangePreset, lastDays, plateNumber, parkNo, startTime, endTime, status, statuses, recognitionType, minAccuracy, recordId, fee, exitTime, targetSpotNo, targetStatus, keyword, userHint, roleHint, username, realName, phone, permissionsText, permissionOperation, permissions, oldPassword, newPassword.\n");
        builder.append("- Use empty string for unknown scalar fields and [] for unknown array fields.\n");
        builder.append("Examples:\n");
        builder.append("- User: 查询4.16之前的数据中心记录\n");
        builder.append("  Output: {\"capabilityCode\":\"DATACENTER_RECORD_QUERY\",\"range\":\"\",\"rangePreset\":\"CUSTOM\",\"lastDays\":\"\",\"plateNumber\":\"\",\"parkNo\":\"\",\"startTime\":\"\",\"endTime\":\"")
                .append(LocalDate.now().getYear())
                .append("-04-16 23:59:59\",\"status\":\"\",\"statuses\":[],\"recognitionType\":\"\",\"minAccuracy\":\"\",\"recordId\":\"\",\"fee\":\"\",\"exitTime\":\"\",\"targetSpotNo\":\"\",\"targetStatus\":\"\",\"keyword\":\"\",\"userHint\":\"\",\"roleHint\":\"\",\"username\":\"\",\"realName\":\"\",\"phone\":\"\",\"permissionsText\":\"\",\"permissionOperation\":\"\",\"permissions\":[],\"oldPassword\":\"\",\"newPassword\":\"\"}\n");
        builder.append("- User: 请把4.16以后数据中心的记录导出成pdf\n");
        builder.append("  Output: {\"capabilityCode\":\"DATACENTER_RECORD_EXPORT_PDF\",\"range\":\"\",\"rangePreset\":\"CUSTOM\",\"lastDays\":\"\",\"plateNumber\":\"\",\"parkNo\":\"\",\"startTime\":\"")
                .append(LocalDate.now().getYear())
                .append("-04-16 00:00:00\",\"endTime\":\"\",\"status\":\"\",\"statuses\":[],\"recognitionType\":\"\",\"minAccuracy\":\"\",\"recordId\":\"\",\"fee\":\"\",\"exitTime\":\"\",\"targetSpotNo\":\"\",\"targetStatus\":\"\",\"keyword\":\"\",\"userHint\":\"\",\"roleHint\":\"\",\"username\":\"\",\"realName\":\"\",\"phone\":\"\",\"permissionsText\":\"\",\"permissionOperation\":\"\",\"permissions\":[],\"oldPassword\":\"\",\"newPassword\":\"\"}\n");
        builder.append("- User: 把管理员角色补上用户查看权限\n");
        builder.append("  Output: {\"capabilityCode\":\"ADMIN_ROLE_PERMISSION_UPDATE\",\"range\":\"\",\"rangePreset\":\"\",\"lastDays\":\"\",\"plateNumber\":\"\",\"parkNo\":\"\",\"startTime\":\"\",\"endTime\":\"\",\"status\":\"\",\"statuses\":[],\"recognitionType\":\"\",\"minAccuracy\":\"\",\"recordId\":\"\",\"fee\":\"\",\"exitTime\":\"\",\"targetSpotNo\":\"\",\"targetStatus\":\"\",\"keyword\":\"\",\"userHint\":\"\",\"roleHint\":\"管理员\",\"username\":\"\",\"realName\":\"\",\"phone\":\"\",\"permissionsText\":\"用户查看权限\",\"permissionOperation\":\"ADD\",\"permissions\":[],\"oldPassword\":\"\",\"newPassword\":\"\"}\n");
        return builder.toString();
    }

    private Map<String, Object> buildRouteTool(Set<AssistantCapability> availableCapabilities) {
        List<String> capabilityCodes = availableCapabilities.stream()
                .sorted(Comparator.comparing(AssistantCapability::code))
                .map(AssistantCapability::code)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        capabilityCodes.add(NO_MATCH);

        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        properties.put("capabilityCode", enumStringProperty(capabilityCodes, "Selected capability code or NO_MATCH."));
        properties.put("range", enumStringProperty(List.of("", "TODAY", "THIS_WEEK", "THIS_MONTH"), "Dashboard range."));
        properties.put("rangePreset", enumStringProperty(
                List.of("", "TODAY", "THIS_WEEK", "THIS_MONTH", "LAST_30_DAYS", "CUSTOM"),
                "Data center range preset."
        ));
        properties.put("lastDays", stringProperty("Recent-day window count such as 10."));
        properties.put("plateNumber", stringProperty("Exact or fuzzy plate number."));
        properties.put("parkNo", stringProperty("Parking spot number."));
        properties.put("startTime", stringProperty("Start time in yyyy-MM-dd HH:mm:ss."));
        properties.put("endTime", stringProperty("End time in yyyy-MM-dd HH:mm:ss."));
        properties.put("status", stringProperty("Single status value."));
        properties.put("statuses", arrayProperty("Multiple status values."));
        properties.put("recognitionType", enumStringProperty(List.of("", "IMAGE", "VIDEO"), "Recognition type."));
        properties.put("minAccuracy", stringProperty("Minimum recognition accuracy."));
        properties.put("recordId", stringProperty("Parking record id."));
        properties.put("fee", stringProperty("Parking fee."));
        properties.put("exitTime", stringProperty("Parking exit time in yyyy-MM-dd HH:mm:ss."));
        properties.put("targetSpotNo", stringProperty("Target parking spot number."));
        properties.put("targetStatus", stringProperty("Target parking spot status."));
        properties.put("keyword", stringProperty("Search keyword."));
        properties.put("userHint", stringProperty("User lookup text."));
        properties.put("roleHint", stringProperty("Role lookup text."));
        properties.put("username", stringProperty("Username."));
        properties.put("realName", stringProperty("Real name."));
        properties.put("phone", stringProperty("Phone number."));
        properties.put("permissionsText", stringProperty("Permission phrase from the user."));
        properties.put("permissionOperation", enumStringProperty(
                List.of("", "REPLACE", "ADD", "REMOVE", "CLEAR", "ALL"),
                "Role permission change mode."
        ));
        properties.put("permissions", arrayProperty("Permission codes when known."));
        properties.put("oldPassword", stringProperty("Old password."));
        properties.put("newPassword", stringProperty("New password."));

        List<String> required = new ArrayList<>(properties.keySet());
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("additionalProperties", false);
        parameters.put("properties", properties);
        parameters.put("required", required);

        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", TOOL_NAME,
                        "description", "Route the latest user message to one assistant capability with structured arguments.",
                        "strict", true,
                        "parameters", parameters
                )
        );
    }

    private Map<String, Object> stringProperty(String description) {
        return Map.of("type", "string", "description", description);
    }

    private Map<String, Object> enumStringProperty(List<String> values, String description) {
        return Map.of("type", "string", "enum", values, "description", description);
    }

    private Map<String, Object> arrayProperty(String description) {
        return Map.of(
                "type", "array",
                "description", description,
                "items", Map.of("type", "string")
        );
    }

    private String normalizeRole(String role) {
        if (!StringUtils.hasText(role)) {
            return null;
        }
        String normalized = role.trim().toLowerCase();
        return switch (normalized) {
            case "user" -> "user";
            case "assistant" -> "assistant";
            default -> null;
        };
    }

    private void putIfHasText(Map<String, Object> params, String key, String value) {
        if (StringUtils.hasText(value)) {
            params.put(key, value.trim());
        }
    }

    private void putListIfPresent(Map<String, Object> params, String key, List<String> values) {
        if (values == null) {
            return;
        }
        List<String> cleaned = values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        if (!cleaned.isEmpty()) {
            params.put(key, cleaned);
        }
    }

    private String extractJsonContent(String content) {
        if (!StringUtils.hasText(content)) {
            return content;
        }
        String trimmed = content.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private record OptionalRoute(
            String capabilityCode,
            String range,
            String rangePreset,
            String lastDays,
            String plateNumber,
            String parkNo,
            String startTime,
            String endTime,
            String status,
            List<String> statuses,
            String recognitionType,
            String minAccuracy,
            String recordId,
            String fee,
            String exitTime,
            String targetSpotNo,
            String targetStatus,
            String keyword,
            String userHint,
            String roleHint,
            String username,
            String realName,
            String phone,
            String permissionsText,
            String permissionOperation,
            List<String> permissions,
            String oldPassword,
            String newPassword
    ) {
    }
}
