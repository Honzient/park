package com.parking.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parking.assistant.AssistantCapability;
import com.parking.assistant.AssistantIntent;
import com.parking.assistant.AssistantParser;
import com.parking.assistant.llm.AssistantLlmParser;
import com.parking.common.PageResult;
import com.parking.common.exception.BusinessException;
import com.parking.domain.dto.admin.AdminUserUpdateDTO;
import com.parking.domain.dto.admin.BatchRoleAssignDTO;
import com.parking.domain.dto.admin.LogPageQueryDTO;
import com.parking.domain.dto.admin.RolePermissionUpdateDTO;
import com.parking.domain.dto.admin.UserPageQueryDTO;
import com.parking.domain.dto.assistant.AssistantChatRequestDTO;
import com.parking.domain.dto.datacenter.DataCenterRecordQueryDTO;
import com.parking.domain.dto.parking.ParkingRecordQueryDTO;
import com.parking.domain.dto.parking.ParkingRecordUpdateDTO;
import com.parking.domain.dto.parking.SpotAssignDTO;
import com.parking.domain.dto.parking.SpotStatusUpdateDTO;
import com.parking.domain.dto.profile.PasswordChangeDTO;
import com.parking.domain.dto.profile.ProfileUpdateDTO;
import com.parking.domain.dto.recognition.RecognitionQueryDTO;
import com.parking.domain.vo.admin.AdminRoleVO;
import com.parking.domain.vo.admin.AdminUserVO;
import com.parking.domain.vo.admin.PermissionNodeVO;
import com.parking.domain.vo.assistant.AssistantCapabilityVO;
import com.parking.domain.vo.assistant.AssistantChatResponseVO;
import com.parking.domain.vo.assistant.AssistantDownloadFileVO;
import com.parking.domain.vo.assistant.AssistantPendingActionVO;
import com.parking.domain.vo.parking.ParkingRecordVO;
import com.parking.domain.vo.parking.ParkingSpotVO;
import com.parking.domain.vo.profile.ProfileVO;
import com.parking.service.AdminService;
import com.parking.service.AssistantService;
import com.parking.service.DashboardService;
import com.parking.service.DataCenterService;
import com.parking.service.ParkingService;
import com.parking.service.ProfileService;
import com.parking.service.RecognitionService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class AssistantServiceImpl implements AssistantService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final DashboardService dashboardService;
    private final ParkingService parkingService;
    private final RecognitionService recognitionService;
    private final DataCenterService dataCenterService;
    private final AdminService adminService;
    private final ProfileService profileService;
    private final AssistantLlmParser assistantLlmParser;
    private final AssistantParser assistantParser;
    private final ObjectMapper objectMapper;

    public AssistantServiceImpl(DashboardService dashboardService,
                                ParkingService parkingService,
                                RecognitionService recognitionService,
                                DataCenterService dataCenterService,
                                AdminService adminService,
                                ProfileService profileService,
                                AssistantLlmParser assistantLlmParser,
                                AssistantParser assistantParser,
                                ObjectMapper objectMapper) {
        this.dashboardService = dashboardService;
        this.parkingService = parkingService;
        this.recognitionService = recognitionService;
        this.dataCenterService = dataCenterService;
        this.adminService = adminService;
        this.profileService = profileService;
        this.assistantLlmParser = assistantLlmParser;
        this.assistantParser = assistantParser;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<AssistantCapabilityVO> listCapabilities() {
        return accessibleCapabilities().stream()
                .sorted(Comparator.comparing(AssistantCapability::code))
                .map(this::toCapabilityVO)
                .toList();
    }

    @Override
    public AssistantChatResponseVO chat(AssistantChatRequestDTO request) {
        Set<AssistantCapability> allowedCapabilities = accessibleCapabilities();
        List<AssistantCapabilityVO> capabilityVOs = capabilityVOs(allowedCapabilities);

        if (request.isConfirm()) {
            return executeConfirmedAction(request.getPendingAction(), allowedCapabilities, capabilityVOs);
        }

        AssistantIntent intent = parseIntent(request, allowedCapabilities);
        if (intent == null) {
            AssistantIntent unauthorizedIntent = parseIntent(request, EnumSet.allOf(AssistantCapability.class));
            if (unauthorizedIntent != null && !allowedCapabilities.contains(unauthorizedIntent.capability())) {
                AssistantCapability capability = unauthorizedIntent.capability();
                return new AssistantChatResponseVO(
                        "\u5f53\u524d\u8d26\u53f7\u6ca1\u6709\u201c" + capability.displayName() + "\u201d\u5bf9\u5e94\u7684\u6743\u9650\uff0c\u52a9\u624b\u4e0d\u80fd\u4ee3\u4f60\u6267\u884c\u8fd9\u4e2a\u529f\u80fd\u3002",
                        capability.code(),
                        false,
                        null,
                        null,
                        buildSuggestions(allowedCapabilities),
                        capabilityVOs
                );
            }

            String conversationalReply = assistantLlmParser.replyConversation(request, allowedCapabilities)
                    .orElse(null);
            if (StringUtils.hasText(conversationalReply)) {
                return new AssistantChatResponseVO(
                        conversationalReply,
                        null,
                        false,
                        null,
                        null,
                        List.of(),
                        capabilityVOs
                );
            }

            return new AssistantChatResponseVO(
                    "\u6211\u6682\u65f6\u6ca1\u80fd\u51c6\u786e\u7406\u89e3\u8fd9\u53e5\u8bdd\u3002\u4f60\u53ef\u4ee5\u7ee7\u7eed\u76f4\u63a5\u63cf\u8ff0\u8981\u67e5\u8be2\u6216\u8981\u4fee\u6539\u7684\u4e1a\u52a1\u5bf9\u8c61\u3002",
                    null,
                    false,
                    null,
                    null,
                    buildSuggestions(allowedCapabilities),
                    capabilityVOs
            );
        }
        AssistantCapability capability = intent.capability();
        if (!allowedCapabilities.contains(capability)) {
            return new AssistantChatResponseVO(
                    "当前账号没有“" + capability.displayName() + "”对应的权限，助手不能代你执行这个功能。",
                    capability.code(),
                    false,
                    null,
                    null,
                    buildSuggestions(allowedCapabilities),
                    capabilityVOs
            );
        }

        if (capability.confirmationRequired()) {
            return buildPendingResponse(capability, intent.params(), capabilityVOs);
        }

        Object data = executeReadCapability(capability, intent.params());
        return new AssistantChatResponseVO(
                buildSuccessMessage(capability),
                capability.code(),
                false,
                null,
                data,
                List.of(),
                capabilityVOs
        );
    }

    private AssistantIntent parseIntent(AssistantChatRequestDTO request, Set<AssistantCapability> availableCapabilities) {
        AssistantIntent intent = assistantLlmParser.parse(request, availableCapabilities);
        if (intent != null) {
            return intent;
        }
        return assistantParser.parse(request.getMessage(), availableCapabilities);
    }

    private AssistantChatResponseVO executeConfirmedAction(Map<String, Object> pendingAction,
                                                           Set<AssistantCapability> allowedCapabilities,
                                                           List<AssistantCapabilityVO> capabilityVOs) {
        if (pendingAction == null || !StringUtils.hasText(asText(pendingAction.get("capabilityCode")))) {
            throw new BusinessException(400, "Missing pending action");
        }

        AssistantCapability capability = AssistantCapability.fromCode(asText(pendingAction.get("capabilityCode")))
                .orElseThrow(() -> new BusinessException(400, "Unknown assistant action"));
        if (!allowedCapabilities.contains(capability)) {
            throw new BusinessException(403, "Current account has no permission for this action");
        }

        Map<String, Object> params = objectMapper.convertValue(pendingAction.get("params"), Map.class);
        Object data = executeWriteCapability(capability, params == null ? Map.of() : params);
        return new AssistantChatResponseVO(
                buildWriteSuccessMessage(capability),
                capability.code(),
                false,
                null,
                data,
                List.of(),
                capabilityVOs
        );
    }

    private AssistantChatResponseVO buildPendingResponse(AssistantCapability capability,
                                                         Map<String, Object> rawParams,
                                                         List<AssistantCapabilityVO> capabilityVOs) {
        Map<String, Object> params = prepareWriteParams(capability, rawParams == null ? Map.of() : rawParams);
        String summary = buildPendingSummary(capability, params);
        AssistantPendingActionVO pendingAction = new AssistantPendingActionVO(capability.code(), summary, params);
        return new AssistantChatResponseVO(
                summary + "。确认后我再执行。",
                capability.code(),
                true,
                pendingAction,
                null,
                List.of(),
                capabilityVOs
        );
    }

    private Object executeReadCapability(AssistantCapability capability, Map<String, Object> params) {
        return switch (capability) {
            case DASHBOARD_REALTIME -> dashboardService.realtime(asTextOrDefault(params.get("range"), "TODAY"));
            case PARKING_RECORD_QUERY -> parkingService.queryRecords(buildParkingQuery(params));
            case PARKING_RECORD_DETAIL -> parkingService.getDetail(asLong(params.get("recordId")));
            case PARKING_SPOT_LIST -> filterSpots(parkingService.listSpots(), normalizeSpotStatusValue(asText(params.get("status"))));
            case PARKING_ASSIGNMENT_VEHICLES -> parkingService.listAssignmentVehicles();
            case RECOGNITION_RECORD_QUERY -> recognitionService.queryRecords(buildRecognitionQuery(params));
            case RECOGNITION_RECORD_EXPORT_EXCEL -> buildDownloadFile(
                    "????-" + LocalDateTime.now().format(FILE_TIMESTAMP_FORMATTER) + ".xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    recognitionService.exportExcel(buildRecognitionQuery(params))
            );
            case RECOGNITION_VIDEO_ACCESS_GUIDE -> recognitionService.cameraAccessGuide();
            case DATACENTER_OVERVIEW -> dataCenterService.overview(buildDataCenterQuery(params));
            case DATACENTER_RECORD_QUERY -> dataCenterService.queryRecords(buildDataCenterQuery(params));
            case DATACENTER_RECORD_EXPORT_EXCEL -> buildDownloadFile(
                    "????????-" + LocalDateTime.now().format(FILE_TIMESTAMP_FORMATTER) + ".xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    dataCenterService.exportExcel(buildDataCenterQuery(params))
            );
            case DATACENTER_RECORD_EXPORT_PDF -> buildDownloadFile(
                    "????????-" + LocalDateTime.now().format(FILE_TIMESTAMP_FORMATTER) + ".pdf",
                    "application/pdf",
                    dataCenterService.exportPdf(buildDataCenterQuery(params))
            );
            case ADMIN_USER_PAGE -> adminService.users(buildUserPageQuery(params));
            case ADMIN_ROLE_LIST -> adminService.roles();
            case ADMIN_PERMISSION_TREE -> adminService.permissionTree();
            case ADMIN_OPERATION_LOGS -> adminService.operationLogs(buildLogPageQuery(params));
            case ADMIN_LOGIN_LOGS -> adminService.loginLogs(buildLogPageQuery(params));
            case PROFILE_ME -> profileService.profile(currentUsername());
            case PROFILE_LOGIN_LOGS -> profileService.loginLogs(currentUsername(), 1, 20);
            default -> throw new BusinessException(400, "Unsupported read capability: " + capability.code());
        };
    }

    private Object executeWriteCapability(AssistantCapability capability, Map<String, Object> params) {
        return switch (capability) {
            case PARKING_RECORD_UPDATE -> {
                Long recordId = asLong(params.get("recordId"));
                ParkingRecordUpdateDTO dto = objectMapper.convertValue(params, ParkingRecordUpdateDTO.class);
                parkingService.updateRecord(recordId, dto);
                yield parkingService.getDetail(recordId);
            }
            case PARKING_SPOT_ASSIGN -> {
                parkingService.assignSpot(objectMapper.convertValue(params, SpotAssignDTO.class));
                yield Map.of("success", true);
            }
            case PARKING_SPOT_STATUS_UPDATE -> {
                parkingService.updateSpotStatus(objectMapper.convertValue(params, SpotStatusUpdateDTO.class));
                yield Map.of("success", true);
            }
            case ADMIN_USER_UPDATE -> {
                adminService.updateUser(objectMapper.convertValue(params, AdminUserUpdateDTO.class),
                        currentUsername(), "/api/assistant/chat", "assistant", "assistant");
                yield resolveUserById(asLong(params.get("id")));
            }
            case ADMIN_ASSIGN_ROLE -> {
                adminService.assignRole(objectMapper.convertValue(params, BatchRoleAssignDTO.class),
                        currentUsername(), "/api/assistant/chat", "assistant", "assistant");
                yield Map.of("success", true);
            }
            case ADMIN_ROLE_PERMISSION_UPDATE -> {
                adminService.updateRolePermissions(objectMapper.convertValue(params, RolePermissionUpdateDTO.class),
                        currentUsername(), "/api/assistant/chat", "assistant", "assistant");
                yield resolveRole(asText(params.get("roleCode")));
            }
            case PROFILE_UPDATE -> {
                String targetUsername = asTextOrDefault(params.get("username"), currentUsername());
                profileService.updateProfile(currentUsername(),
                        objectMapper.convertValue(params, ProfileUpdateDTO.class),
                        "/api/assistant/chat", "assistant", "assistant");
                yield profileService.profile(targetUsername);
            }
            case PROFILE_PASSWORD_CHANGE -> {
                profileService.changePassword(currentUsername(),
                        objectMapper.convertValue(params, PasswordChangeDTO.class),
                        "/api/assistant/chat", "assistant", "assistant");
                yield Map.of("success", true);
            }
            default -> throw new BusinessException(400, "Unsupported write capability: " + capability.code());
        };
    }

    private Map<String, Object> prepareWriteParams(AssistantCapability capability, Map<String, Object> params) {
        return switch (capability) {
            case PARKING_RECORD_UPDATE -> prepareParkingRecordUpdate(params);
            case PARKING_SPOT_ASSIGN -> prepareSpotAssign(params);
            case PARKING_SPOT_STATUS_UPDATE -> prepareSpotStatusUpdate(params);
            case ADMIN_USER_UPDATE -> prepareAdminUserUpdate(params);
            case ADMIN_ASSIGN_ROLE -> prepareRoleAssign(params);
            case ADMIN_ROLE_PERMISSION_UPDATE -> prepareRolePermissionUpdate(params);
            case PROFILE_UPDATE -> prepareProfileUpdate(params);
            case PROFILE_PASSWORD_CHANGE -> prepareProfilePasswordChange(params);
            default -> params;
        };
    }

    private Map<String, Object> prepareParkingRecordUpdate(Map<String, Object> params) {
        Long recordId = asLong(params.get("recordId"));
        if (recordId == null) {
            throw new BusinessException(400, "Please specify the parking record id");
        }

        ParkingRecordVO current = parkingService.getDetail(recordId);
        String status = normalizeRecordStatusValue(asTextOrDefault(params.get("status"), current.status()));
        String exitTime = status.equals("未出场") ? null : asTextOrDefault(params.get("exitTime"), current.exitTime());
        String feeText = asTextOrDefault(params.get("fee"), current.fee() == null ? "0" : current.fee().toPlainString());

        Map<String, Object> prepared = new HashMap<>();
        prepared.put("recordId", recordId);
        prepared.put("plateNumber", asTextOrDefault(params.get("plateNumber"), current.plateNumber()));
        prepared.put("parkNo", asTextOrDefault(params.get("parkNo"), current.parkNo()));
        prepared.put("entryTime", resolveDateTimeParam(current.entryTime(), false));
        prepared.put("exitTime", resolveDateTimeParam(exitTime, true));
        prepared.put("fee", new BigDecimal(feeText));
        prepared.put("status", status);
        return prepared;
    }

    private Map<String, Object> prepareSpotAssign(Map<String, Object> params) {
        String plateNumber = asText(params.get("plateNumber"));
        String targetSpotNo = asText(params.get("targetSpotNo"));
        if (!StringUtils.hasText(plateNumber) || !StringUtils.hasText(targetSpotNo)) {
            throw new BusinessException(400, "请明确提供车牌号和目标车位，例如“把京A12345分配到A-01车位”");
        }
        return Map.of("plateNumber", plateNumber, "targetSpotNo", targetSpotNo);
    }

    private Map<String, Object> prepareSpotStatusUpdate(Map<String, Object> params) {
        String spotNo = asText(params.get("spotNo"));
        String targetStatus = normalizeSpotStatusValue(asText(params.get("targetStatus")));
        if (!StringUtils.hasText(spotNo) || !StringUtils.hasText(targetStatus)) {
            throw new BusinessException(400, "请明确提供车位号和目标状态，例如“把A-01车位改成维护中”");
        }
        Map<String, Object> prepared = new HashMap<>();
        prepared.put("spotNo", spotNo);
        prepared.put("targetStatus", targetStatus);
        if (StringUtils.hasText(asText(params.get("plateNumber")))) {
            prepared.put("plateNumber", asText(params.get("plateNumber")));
        }
        return prepared;
    }

    private Map<String, Object> prepareAdminUserUpdate(Map<String, Object> params) {
        String userHint = asText(params.get("userHint"));
        if (!StringUtils.hasText(userHint)) {
            throw new BusinessException(400, "请明确提供要修改的用户");
        }

        AdminUserVO current = resolveUser(userHint);
        Map<String, Object> prepared = new HashMap<>();
        prepared.put("id", current.id());
        prepared.put("username", asTextOrDefault(params.get("username"), current.username()));
        prepared.put("realName", asTextOrDefault(params.get("realName"), current.realName()));
        prepared.put("phone", asTextOrDefault(params.get("phone"), current.phone()));
        prepared.put("roleCode", current.roleCode());
        prepared.put("status", normalizeAdminUserStatus(asTextOrDefault(params.get("status"), current.status())));
        return prepared;
    }

    private Map<String, Object> prepareRoleAssign(Map<String, Object> params) {
        String userHint = asText(params.get("userHint"));
        String roleHint = asText(params.get("roleHint"));
        if (!StringUtils.hasText(userHint) || !StringUtils.hasText(roleHint)) {
            throw new BusinessException(400, "请明确提供用户和角色，例如“给张三分配管理员角色”");
        }

        List<AdminUserVO> users = resolveUsers(userHint);
        AdminRoleVO role = resolveRole(roleHint);
        List<Long> userIds = users.stream().map(AdminUserVO::id).toList();
        String userNames = users.stream().map(AdminUserVO::username).toList().toString();
        return Map.of(
                "userIds", userIds,
                "roleCode", role.roleCode(),
                "userName", userNames,
                "roleName", role.roleName()
        );
    }

    private Map<String, Object> prepareRolePermissionUpdate(Map<String, Object> params) {
        String roleHint = asText(params.get("roleHint"));
        String permissionsText = asText(params.get("permissionsText"));
        if (!StringUtils.hasText(roleHint)) {
            throw new BusinessException(400, "请明确要修改哪个角色的权限");
        }
        if (permissionsText == null) {
            throw new BusinessException(400, "请明确要设置哪些权限");
        }

        AdminRoleVO role = resolveRole(roleHint);
        String permissionOperation = normalizePermissionOperation(asText(params.get("permissionOperation")));
        List<String> requestedPermissions = resolveRequestedPermissionCodes(params, permissionsText, permissionOperation);
        List<String> permissions = applyPermissionOperation(permissionOperation, role.permissions(), requestedPermissions);
        Map<String, Object> prepared = new HashMap<>();
        prepared.put("roleCode", role.roleCode());
        prepared.put("roleName", role.roleName());
        prepared.put("permissions", permissions);
        prepared.put("permissionOperation", permissionOperation);
        return prepared;
    }

    private List<String> resolveRequestedPermissionCodes(Map<String, Object> params,
                                                         String permissionsText,
                                                         String permissionOperation) {
        List<String> permissionCodes = resolvePermissionCodes(asStringList(params.get("permissions")));
        if (!permissionCodes.isEmpty()) {
            return permissionCodes;
        }
        if (StringUtils.hasText(permissionsText)) {
            return resolvePermissionCodes(permissionsText);
        }
        if ("CLEAR".equals(permissionOperation)) {
            return List.of();
        }
        if ("ALL".equals(permissionOperation)) {
            return availablePermissionCodes();
        }
        return List.of();
    }

    private List<String> applyPermissionOperation(String permissionOperation,
                                                  List<String> currentPermissions,
                                                  List<String> requestedPermissions) {
        List<String> current = distinctPermissionCodes(currentPermissions);
        List<String> requested = distinctPermissionCodes(requestedPermissions);

        return switch (permissionOperation) {
            case "ADD" -> {
                requirePermissionsForRoleEdit(requested, permissionOperation);
                LinkedHashSet<String> merged = new LinkedHashSet<>(current);
                merged.addAll(requested);
                yield List.copyOf(merged);
            }
            case "REMOVE" -> {
                requirePermissionsForRoleEdit(requested, permissionOperation);
                yield current.stream()
                        .filter(code -> !requested.contains(code))
                        .toList();
            }
            case "CLEAR" -> List.of();
            case "ALL" -> availablePermissionCodes();
            case "REPLACE" -> {
                requirePermissionsForRoleEdit(requested, permissionOperation);
                yield requested;
            }
            default -> {
                requirePermissionsForRoleEdit(requested, "REPLACE");
                yield requested;
            }
        };
    }

    private void requirePermissionsForRoleEdit(List<String> permissions, String permissionOperation) {
        if (permissions.isEmpty()) {
            throw new BusinessException(400, "No permissions resolved for role permission operation: " + permissionOperation);
        }
    }

    private List<String> resolvePermissionCodes(String permissionsText) {
        String normalizedText = asText(permissionsText);
        if (!StringUtils.hasText(normalizedText)) {
            throw new BusinessException(400, "请明确要设置哪些权限");
        }

        List<String> availableCodes = availablePermissionCodes();
        if (matchesPermissionShortcut(normalizedText, "全部权限", "所有权限", "全部", "所有")) {
            return availableCodes;
        }
        if (matchesPermissionShortcut(normalizedText, "清空权限", "空权限", "无权限", "清空", "无")) {
            return List.of();
        }

        Map<String, String> aliasMap = permissionAliasMap(availableCodes);
        String splitReadyText = normalizedText
                .replace("，", ",")
                .replace("、", ",")
                .replace("；", ",")
                .replace(";", ",")
                .replace("以及", ",")
                .replace("并且", ",")
                .replace("和", ",");

        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        for (String token : splitReadyText.split(",")) {
            String permissionCode = resolvePermissionCodeToken(token, aliasMap, availableCodes);
            if (permissionCode != null) {
                resolved.add(permissionCode);
            }
        }

        if (resolved.isEmpty()) {
            throw new BusinessException(400, "未识别到可设置的权限，请使用权限名称或权限编码");
        }
        return List.copyOf(resolved);
    }

    private List<String> resolvePermissionCodes(Collection<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return List.of();
        }

        List<String> availableCodes = availablePermissionCodes();
        Map<String, String> aliasMap = permissionAliasMap(availableCodes);
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        for (String permission : permissions) {
            String permissionCode = resolvePermissionCodeToken(permission, aliasMap, availableCodes);
            if (permissionCode != null) {
                resolved.add(permissionCode);
            }
        }
        return List.copyOf(resolved);
    }

    private String resolvePermissionCodeToken(String token, Map<String, String> aliasMap, List<String> availableCodes) {
        String normalizedToken = asText(token);
        if (!StringUtils.hasText(normalizedToken)) {
            return null;
        }

        if (availableCodes.contains(normalizedToken)) {
            return normalizedToken;
        }

        String lookupKey = normalizePermissionLookupKey(normalizedToken);
        String permissionCode = aliasMap.get(lookupKey);
        if (permissionCode != null) {
            return permissionCode;
        }

        String trimmedToken = normalizedToken.replace("权限", "");
        permissionCode = aliasMap.get(normalizePermissionLookupKey(trimmedToken));
        if (permissionCode != null) {
            return permissionCode;
        }

        throw new BusinessException(400, "未识别权限：" + normalizedToken);
    }

    private Map<String, String> permissionAliasMap(List<String> availableCodes) {
        Map<String, String> aliasMap = new HashMap<>();
        for (String code : availableCodes) {
            aliasMap.put(normalizePermissionLookupKey(code), code);
        }

        putPermissionAlias(aliasMap, "首页", "dashboard:view");
        putPermissionAlias(aliasMap, "查看首页", "dashboard:view");
        putPermissionAlias(aliasMap, "车位详情", "dashboard:spot:detail");
        putPermissionAlias(aliasMap, "查看车位详情", "dashboard:spot:detail");
        putPermissionAlias(aliasMap, "停车记录", "parking:query");
        putPermissionAlias(aliasMap, "进出记录", "parking:query");
        putPermissionAlias(aliasMap, "查看停车记录", "parking:query");
        putPermissionAlias(aliasMap, "车位分配", "parking:assign");
        putPermissionAlias(aliasMap, "识别记录", "recognition:query");
        putPermissionAlias(aliasMap, "查看识别记录", "recognition:query");
        putPermissionAlias(aliasMap, "图片识别", "recognition:image");
        putPermissionAlias(aliasMap, "视频识别", "recognition:video");
        putPermissionAlias(aliasMap, "识别导出", "recognition:export");
        putPermissionAlias(aliasMap, "数据中心", "datacenter:query");
        putPermissionAlias(aliasMap, "查看数据中心", "datacenter:query");
        putPermissionAlias(aliasMap, "导出excel", "datacenter:export:excel");
        putPermissionAlias(aliasMap, "excel导出", "datacenter:export:excel");
        putPermissionAlias(aliasMap, "导出pdf", "datacenter:export:pdf");
        putPermissionAlias(aliasMap, "pdf导出", "datacenter:export:pdf");
        putPermissionAlias(aliasMap, "用户管理", "admin:user:view");
        putPermissionAlias(aliasMap, "分配角色", "admin:user:assign-role");
        putPermissionAlias(aliasMap, "角色管理", "admin:role:view");
        putPermissionAlias(aliasMap, "角色权限编辑", "admin:role:edit");
        putPermissionAlias(aliasMap, "编辑角色权限", "admin:role:edit");
        putPermissionAlias(aliasMap, "日志查看", "admin:log:view");
        putPermissionAlias(aliasMap, "个人中心", "profile:view");
        putPermissionAlias(aliasMap, "查看个人信息", "profile:view");
        putPermissionAlias(aliasMap, "编辑个人信息", "profile:edit");
        putPermissionAlias(aliasMap, "修改个人信息", "profile:edit");
        putPermissionAlias(aliasMap, "修改密码", "profile:password");
        putPermissionAlias(aliasMap, "用户查看", "admin:user:view");
        putPermissionAlias(aliasMap, "用户查看权限", "admin:user:view");
        putPermissionAlias(aliasMap, "查看用户", "admin:user:view");
        return aliasMap;
    }

    private void putPermissionAlias(Map<String, String> aliasMap, String alias, String permissionCode) {
        aliasMap.put(normalizePermissionLookupKey(alias), permissionCode);
    }

    private List<String> availablePermissionCodes() {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        collectPermissionCodes(adminService.permissionTree(), codes);
        return List.copyOf(codes);
    }

    private void collectPermissionCodes(List<PermissionNodeVO> nodes, Set<String> collector) {
        for (PermissionNodeVO node : nodes) {
            if (node.key() != null && node.key().contains(":")) {
                collector.add(node.key());
            }
            if (node.children() != null && !node.children().isEmpty()) {
                collectPermissionCodes(node.children(), collector);
            }
        }
    }

    private boolean matchesPermissionShortcut(String input, String... candidates) {
        String lookupKey = normalizePermissionLookupKey(input);
        for (String candidate : candidates) {
            if (lookupKey.equals(normalizePermissionLookupKey(candidate))) {
                return true;
            }
        }
        return false;
    }

    private String normalizePermissionLookupKey(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> prepareProfileUpdate(Map<String, Object> params) {
        ProfileVO currentProfile = profileService.profile(currentUsername());
        String username = asTextOrDefault(params.get("username"), currentProfile.username());
        String realName = asTextOrDefault(params.get("realName"), currentProfile.realName());
        String phone = asTextOrDefault(params.get("phone"), currentProfile.phone());
        if (!StringUtils.hasText(username) || !StringUtils.hasText(realName) || !StringUtils.hasText(phone)) {
            throw new BusinessException(400, "请至少提供要修改的用户名、姓名或手机号");
        }
        return Map.of(
                "username", username,
                "realName", realName,
                "phone", phone
        );
    }

    private Map<String, Object> prepareProfilePasswordChange(Map<String, Object> params) {
        String oldPassword = asText(params.get("oldPassword"));
        String newPassword = asText(params.get("newPassword"));
        if (!StringUtils.hasText(oldPassword) || !StringUtils.hasText(newPassword)) {
            throw new BusinessException(400, "请同时提供旧密码和新密码");
        }
        return Map.of(
                "oldPassword", oldPassword,
                "newPassword", newPassword
        );
    }

    private ParkingRecordQueryDTO buildParkingQuery(Map<String, Object> params) {
        ParkingRecordQueryDTO dto = new ParkingRecordQueryDTO();
        dto.setAdvanced(true);
        dto.setSortField("entryTime");
        dto.setSortOrder("desc");
        dto.setPageNo(1);
        dto.setPageSize(20);
        dto.setPlateNumber(asText(params.get("plateNumber")));
        dto.setParkNo(asText(params.get("parkNo")));
        dto.setStartTime(resolveStartTime(params));
        dto.setEndTime(resolveEndTime(params));
        dto.setStatuses(resolveRecordStatuses(params));
        return dto;
    }

    private RecognitionQueryDTO buildRecognitionQuery(Map<String, Object> params) {
        RecognitionQueryDTO dto = new RecognitionQueryDTO();
        dto.setAdvanced(true);
        dto.setSortField("recognitionTime");
        dto.setSortOrder("desc");
        dto.setPageNo(1);
        dto.setPageSize(20);
        dto.setPlateNumber(asText(params.get("plateNumber")));
        dto.setRecognitionType(normalizeRecognitionType(asText(params.get("recognitionType"))));
        dto.setStartTime(resolveStartTime(params));
        dto.setEndTime(resolveEndTime(params));
        String minAccuracy = asText(params.get("minAccuracy"));
        if (StringUtils.hasText(minAccuracy)) {
            dto.setMinAccuracy(new BigDecimal(minAccuracy));
        }
        return dto;
    }

    private DataCenterRecordQueryDTO buildDataCenterQuery(Map<String, Object> params) {
        DataCenterRecordQueryDTO dto = new DataCenterRecordQueryDTO();
        dto.setAdvanced(true);
        dto.setSortField("entryTime");
        dto.setSortOrder("desc");
        dto.setPageNo(1);
        dto.setPageSize(20);
        dto.setPlateNumber(asText(params.get("plateNumber")));
        dto.setParkNo(asText(params.get("parkNo")));
        LocalDateTime startTime = resolveStartTime(params);
        LocalDateTime endTime = resolveEndTime(params);
        dto.setStartTime(startTime);
        dto.setEndTime(endTime);
        dto.setRangePreset(resolveDataCenterRangePreset(params, startTime, endTime));
        dto.setStatuses(resolveRecordStatuses(params));
        return dto;
    }

    private List<String> resolveRecordStatuses(Map<String, Object> params) {
        List<String> statuses = asStringList(params.get("statuses")).stream()
                .map(this::normalizeRecordStatusValue)
                .filter(StringUtils::hasText)
                .toList();
        if (!statuses.isEmpty()) {
            return statuses;
        }

        String status = normalizeRecordStatusValue(asText(params.get("status")));
        return StringUtils.hasText(status) ? List.of(status) : null;
    }

    private String resolveDataCenterRangePreset(Map<String, Object> params,
                                                LocalDateTime startTime,
                                                LocalDateTime endTime) {
        if (startTime != null || endTime != null) {
            return "CUSTOM";
        }
        return normalizeRangePreset(asTextOrDefault(params.get("rangePreset"), "LAST_30_DAYS"));
    }

    private UserPageQueryDTO buildUserPageQuery(Map<String, Object> params) {
        UserPageQueryDTO dto = new UserPageQueryDTO();
        dto.setPageNo(1);
        dto.setPageSize(20);
        dto.setKeyword(asText(params.get("keyword")));
        return dto;
    }

    private LogPageQueryDTO buildLogPageQuery(Map<String, Object> params) {
        LogPageQueryDTO dto = new LogPageQueryDTO();
        dto.setPageNo(1);
        dto.setPageSize(20);
        dto.setKeyword(asText(params.get("keyword")));
        return dto;
    }

    private AssistantDownloadFileVO buildDownloadFile(String fileName, String contentType, byte[] fileBytes) {
        return new AssistantDownloadFileVO(fileName, contentType, fileBytes);
    }

    private List<ParkingSpotVO> filterSpots(List<ParkingSpotVO> spots, String status) {
        if (!StringUtils.hasText(status)) {
            return spots;
        }
        return spots.stream()
                .filter(item -> status.equalsIgnoreCase(item.status()))
                .toList();
    }

    private String buildPendingSummary(AssistantCapability capability, Map<String, Object> params) {
        return switch (capability) {
            case PARKING_RECORD_UPDATE -> "将修改停车记录 " + params.get("recordId");
            case PARKING_SPOT_ASSIGN -> "将把 " + params.get("plateNumber") + " 分配到车位 " + params.get("targetSpotNo");
            case PARKING_SPOT_STATUS_UPDATE -> "将把车位 " + params.get("spotNo") + " 状态改为 " + params.get("targetStatus");
            case ADMIN_USER_UPDATE -> "将修改用户 " + params.get("username");
            case ADMIN_ASSIGN_ROLE -> "将给用户 " + params.get("userName") + " 分配角色 " + params.get("roleName");
            case ADMIN_ROLE_PERMISSION_UPDATE -> "将把角色 " + params.get("roleName") + " 的权限更新为 " + describeValues(params.get("permissions"));
            case PROFILE_UPDATE -> "将更新当前用户资料";
            case PROFILE_PASSWORD_CHANGE -> "将修改当前用户密码";
            default -> "将执行 " + capability.displayName();
        };
    }

    private String buildSuccessMessage(AssistantCapability capability) {
        return switch (capability) {
            case DASHBOARD_REALTIME -> "已读取首页实时概览。";
            case PARKING_RECORD_QUERY -> "已查询车辆进出记录。";
            case PARKING_RECORD_DETAIL -> "已读取停车记录详情。";
            case PARKING_SPOT_LIST -> "已查询车位状态。";
            case PARKING_ASSIGNMENT_VEHICLES -> "已查询待分配车辆。";
            case RECOGNITION_RECORD_QUERY -> "已查询识别记录。";
            case RECOGNITION_RECORD_EXPORT_EXCEL -> "??????????";
            case RECOGNITION_VIDEO_ACCESS_GUIDE -> "已读取视频接入说明。";
            case DATACENTER_OVERVIEW -> "已查询数据中心概览。";
            case DATACENTER_RECORD_QUERY -> "已查询数据中心记录。";
            case DATACENTER_RECORD_EXPORT_EXCEL -> "??????????";
            case DATACENTER_RECORD_EXPORT_PDF -> "??????????";
            case ADMIN_USER_PAGE -> "已查询用户列表。";
            case ADMIN_ROLE_LIST -> "已查询角色列表。";
            case ADMIN_PERMISSION_TREE -> "已查询权限树。";
            case ADMIN_OPERATION_LOGS -> "已查询操作日志。";
            case ADMIN_LOGIN_LOGS -> "已查询登录日志。";
            case PROFILE_ME -> "已读取当前用户资料。";
            case PROFILE_LOGIN_LOGS -> "已读取当前用户登录日志。";
            default -> "已完成查询。";
        };
    }

    private String buildWriteSuccessMessage(AssistantCapability capability) {
        return switch (capability) {
            case PARKING_RECORD_UPDATE -> "停车记录已更新。";
            case PARKING_SPOT_ASSIGN -> "车位分配已执行。";
            case PARKING_SPOT_STATUS_UPDATE -> "车位状态修改已执行。";
            case ADMIN_USER_UPDATE -> "用户信息已更新。";
            case ADMIN_ASSIGN_ROLE -> "角色分配已执行。";
            case ADMIN_ROLE_PERMISSION_UPDATE -> "角色权限已修改。";
            case PROFILE_UPDATE -> "个人资料已更新。";
            case PROFILE_PASSWORD_CHANGE -> "密码已修改，请重新登录。";
            default -> "操作已执行。";
        };
    }

    private List<String> buildSuggestions(Set<AssistantCapability> capabilities) {
        List<String> suggestions = new ArrayList<>();
        if (capabilities.contains(AssistantCapability.PARKING_RECORD_QUERY)) {
            suggestions.add("查最近10天京A的进出记录");
        }
        if (capabilities.contains(AssistantCapability.PARKING_RECORD_DETAIL)) {
            suggestions.add("查看停车记录12详情");
        }
        if (capabilities.contains(AssistantCapability.PARKING_SPOT_LIST)) {
            suggestions.add("查看当前空闲车位");
        }
        if (capabilities.contains(AssistantCapability.RECOGNITION_RECORD_EXPORT_EXCEL)) {
            suggestions.add("????7??????????");
        }
        if (capabilities.contains(AssistantCapability.DATACENTER_RECORD_EXPORT_EXCEL)) {
            suggestions.add("????????????");
        }
        if (capabilities.contains(AssistantCapability.DATACENTER_RECORD_EXPORT_PDF)) {
            suggestions.add("????????????");
        }
        if (capabilities.contains(AssistantCapability.ADMIN_USER_UPDATE)) {
            suggestions.add("禁用用户张三");
        }
        if (capabilities.contains(AssistantCapability.ADMIN_ROLE_PERMISSION_UPDATE)) {
            suggestions.add("把管理员角色权限改成用户管理、角色管理");
        }
        if (capabilities.contains(AssistantCapability.PROFILE_PASSWORD_CHANGE)) {
            suggestions.add("把我的密码从OldPass1改成NewPass2");
        }
        if (capabilities.contains(AssistantCapability.PROFILE_ME)) {
            suggestions.add("查看我的个人信息");
        }
        return suggestions;
    }

    private String describeValues(Object value) {
        if (value instanceof Collection<?> collection) {
            if (collection.isEmpty()) {
                return "空权限集";
            }
            return collection.stream()
                    .map(String::valueOf)
                    .toList()
                    .toString();
        }
        return String.valueOf(value);
    }

    private List<AdminUserVO> resolveUsers(String hintText) {
        String normalized = hintText == null ? "" : hintText
                .replace("用户", "")
                .trim();
        if (!StringUtils.hasText(normalized)) {
            throw new BusinessException(400, "请明确要分配角色的用户");
        }

        String splitReady = normalized
                .replace("，", ",")
                .replace("、", ",")
                .replace("；", ",")
                .replace(";", ",")
                .replace("以及", ",")
                .replace("和", ",");

        LinkedHashSet<String> hints = new LinkedHashSet<>();
        for (String token : splitReady.split(",")) {
            String userHint = token.trim();
            if (StringUtils.hasText(userHint)) {
                hints.add(userHint);
            }
        }
        if (hints.isEmpty()) {
            hints.add(normalized);
        }

        return hints.stream()
                .map(this::resolveUser)
                .toList();
    }

    private AdminUserVO resolveUser(String hint) {
        UserPageQueryDTO queryDTO = new UserPageQueryDTO();
        queryDTO.setPageNo(1);
        queryDTO.setPageSize(200);
        queryDTO.setKeyword(hint);
        PageResult<AdminUserVO> pageResult = adminService.users(queryDTO);
        List<AdminUserVO> matches = pageResult.records().stream()
                .filter(item -> equalsIgnoreCase(hint, item.username())
                        || Objects.equals(hint, item.realName())
                        || item.username().contains(hint)
                        || item.realName().contains(hint))
                .toList();
        if (matches.isEmpty()) {
            throw new BusinessException(404, "未找到匹配的用户：" + hint);
        }
        if (matches.size() > 1) {
            throw new BusinessException(400, "匹配到多个用户，请提供更精确的用户名：" + hint);
        }
        return matches.get(0);
    }

    private AdminUserVO resolveUserById(Long userId) {
        UserPageQueryDTO queryDTO = new UserPageQueryDTO();
        queryDTO.setPageNo(1);
        queryDTO.setPageSize(500);
        PageResult<AdminUserVO> pageResult = adminService.users(queryDTO);
        return pageResult.records().stream()
                .filter(item -> Objects.equals(item.id(), userId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(404, "User not found"));
    }

    private AdminRoleVO resolveRole(String hint) {
        String normalizedHint = normalizeRoleHint(hint);
        List<AdminRoleVO> roles = adminService.roles();
        List<AdminRoleVO> matches = roles.stream()
                .filter(item -> equalsIgnoreCase(normalizedHint, item.roleCode())
                        || Objects.equals(normalizedHint, item.roleName())
                        || item.roleCode().contains(normalizedHint)
                        || item.roleName().contains(normalizedHint)
                        || equalsIgnoreCase(hint, item.roleCode())
                        || Objects.equals(hint, item.roleName())
                        || item.roleCode().contains(hint)
                        || item.roleName().contains(hint))
                .toList();
        if (matches.isEmpty()) {
            throw new BusinessException(404, "未找到匹配的角色：" + hint);
        }
        if (matches.size() > 1) {
            throw new BusinessException(400, "匹配到多个角色，请提供更精确的角色名：" + hint);
        }
        return matches.get(0);
    }

    private String normalizeRoleHint(String hint) {
        if (!StringUtils.hasText(hint)) {
            return hint;
        }
        String normalized = hint.trim();
        if (normalized.contains("管理员") || normalized.contains("超级管理员")) {
            return "ADMIN";
        }
        if (normalized.contains("操作员") || normalized.contains("运营")) {
            return "OPERATOR";
        }
        if (normalized.contains("审计") || normalized.contains("审核")) {
            return "AUDITOR";
        }
        if (normalized.contains("查看") || normalized.contains("访客") || normalized.contains("只读")) {
            return "VIEWER";
        }
        return normalized;
    }

    private Set<AssistantCapability> accessibleCapabilities() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return EnumSet.noneOf(AssistantCapability.class);
        }
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(item -> item.getAuthority())
                .collect(java.util.stream.Collectors.toSet());

        EnumSet<AssistantCapability> capabilities = EnumSet.noneOf(AssistantCapability.class);
        for (AssistantCapability capability : AssistantCapability.values()) {
            if (StringUtils.hasText(capability.permission()) && !authorities.contains(capability.permission())) {
                continue;
            }
            capabilities.add(capability);
        }
        return capabilities;
    }

    private List<AssistantCapabilityVO> capabilityVOs(Set<AssistantCapability> capabilities) {
        return capabilities.stream()
                .sorted(Comparator.comparing(AssistantCapability::code))
                .map(this::toCapabilityVO)
                .toList();
    }

    private AssistantCapabilityVO toCapabilityVO(AssistantCapability capability) {
        return new AssistantCapabilityVO(
                capability.code(),
                capability.displayName(),
                capability.description(),
                capability.readOnly(),
                capability.confirmationRequired()
        );
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "unknown" : authentication.getName();
    }

    private LocalDateTime parseDateTime(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return resolveDateTimeParam(value, false);
    }

    private LocalDateTime resolveStartTime(Map<String, Object> params) {
        LocalDateTime startTime = resolveDateTimeParam(params.get("startTime"), false);
        if (startTime != null) {
            return startTime;
        }
        Integer lastDays = asInteger(params.get("lastDays"));
        if (lastDays != null && lastDays > 0) {
            return LocalDate.now().minusDays(lastDays - 1L).atStartOfDay();
        }
        return null;
    }

    private LocalDateTime resolveEndTime(Map<String, Object> params) {
        LocalDateTime endTime = resolveDateTimeParam(params.get("endTime"), true);
        if (endTime != null) {
            return endTime;
        }
        Integer lastDays = asInteger(params.get("lastDays"));
        if (lastDays != null && lastDays > 0) {
            return LocalDate.now().atTime(23, 59, 59);
        }
        return null;
    }

    private LocalDateTime resolveDateTimeParam(Object value, boolean endOfDayIfDateOnly) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }

        String text = asText(value);
        if (!StringUtils.hasText(text)) {
            return null;
        }

        List<DateTimeFormatter> dateTimeFormatters = List.of(
                DATE_TIME_FORMATTER,
                DateTimeFormatter.ofPattern("yyyy-M-d H:m:s"),
                DateTimeFormatter.ofPattern("yyyy-M-d H:m"),
                DateTimeFormatter.ofPattern("yyyy/M/d H:m:s"),
                DateTimeFormatter.ofPattern("yyyy/M/d H:m"),
                DateTimeFormatter.ofPattern("yyyy.M.d H:m:s"),
                DateTimeFormatter.ofPattern("yyyy.M.d H:m"),
                DateTimeFormatter.ISO_LOCAL_DATE_TIME
        );
        for (DateTimeFormatter formatter : dateTimeFormatters) {
            try {
                return LocalDateTime.parse(text, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }

        LocalDate date = resolveDateValue(text);
        if (date != null) {
            return endOfDayIfDateOnly ? date.atTime(23, 59, 59) : date.atStartOfDay();
        }
        return null;
    }

    private LocalDate resolveDateValue(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }

        List<DateTimeFormatter> fullDateFormatters = List.of(
                DateTimeFormatter.ofPattern("yyyy-M-d"),
                DateTimeFormatter.ofPattern("yyyy/M/d"),
                DateTimeFormatter.ofPattern("yyyy.M.d"),
                DateTimeFormatter.ofPattern("yyyy年M月d日")
        );
        for (DateTimeFormatter formatter : fullDateFormatters) {
            try {
                return LocalDate.parse(value, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }

        try {
            String normalized = normalizeMonthDayValue(value);
            return LocalDate.parse(LocalDate.now().getYear() + "-" + normalized, DateTimeFormatter.ofPattern("yyyy-M-d"));
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private String normalizeMonthDayValue(String value) {
        return value.replace('年', '-')
                .replace("月", "-")
                .replace("日", "")
                .replace('/', '-')
                .replace('.', '-');
    }

    private String normalizeRangePreset(String value) {
        if (!StringUtils.hasText(value)) {
            return "LAST_30_DAYS";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "TODAY", "THIS_WEEK", "THIS_MONTH", "LAST_30_DAYS", "CUSTOM" -> normalized;
            default -> "LAST_30_DAYS";
        };
    }

    private String normalizeRecognitionType(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "IMAGE", "PICTURE", "PHOTO" -> "IMAGE";
            case "VIDEO" -> "VIDEO";
            default -> {
                if (value.contains("图") || value.contains("图片") || value.contains("照片")) {
                    yield "IMAGE";
                }
                if (value.contains("视频")) {
                    yield "VIDEO";
                }
                yield normalized;
            }
        };
    }

    private String normalizeRecordStatusValue(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("未出") || normalized.contains("在场") || normalized.contains("active")) {
            return "未出场";
        }
        if (normalized.contains("已出") || normalized.contains("离场") || normalized.contains("completed")
                || normalized.contains("closed") || normalized.contains("exit")) {
            return "已出场";
        }
        return value.trim();
    }

    private String normalizeSpotStatusValue(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("FREE".equals(normalized) || value.contains("空闲")) {
            return "FREE";
        }
        if ("OCCUPIED".equals(normalized) || value.contains("占用")) {
            return "OCCUPIED";
        }
        if ("MAINTENANCE".equals(normalized) || value.contains("维护")) {
            return "MAINTENANCE";
        }
        if ("RESERVED".equals(normalized) || value.contains("预约")) {
            return "RESERVED";
        }
        return normalized;
    }

    private String normalizeAdminUserStatus(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("ENABLED".equals(normalized) || value.contains("启用")) {
            return "ENABLED";
        }
        if ("DISABLED".equals(normalized) || value.contains("禁用")) {
            return "DISABLED";
        }
        return normalized;
    }

    private String normalizePermissionOperation(String value) {
        if (!StringUtils.hasText(value)) {
            return "REPLACE";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("ADD".equals(normalized) || value.contains("增加") || value.contains("添加")
                || value.contains("授予") || value.contains("追加")) {
            return "ADD";
        }
        if ("REMOVE".equals(normalized) || value.contains("移除") || value.contains("删除")
                || value.contains("撤销") || value.contains("去掉")) {
            return "REMOVE";
        }
        if ("CLEAR".equals(normalized) || value.contains("清空") || value.contains("全部移除")) {
            return "CLEAR";
        }
        if ("ALL".equals(normalized) || value.contains("全部权限") || value.contains("所有权限")) {
            return "ALL";
        }
        return "REPLACE";
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = asText(value);
        return StringUtils.hasText(text) ? Long.parseLong(text) : null;
    }

    private Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = asText(value);
        return StringUtils.hasText(text) ? Integer.parseInt(text) : null;
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private List<String> asStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .toList();
        }
        String text = asText(value);
        return StringUtils.hasText(text) ? List.of(text) : List.of();
    }

    private List<String> distinctPermissionCodes(Collection<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String permission : permissions) {
            if (StringUtils.hasText(permission)) {
                distinct.add(permission.trim());
            }
        }
        return List.copyOf(distinct);
    }

    private String asTextOrDefault(Object value, String defaultValue) {
        String text = asText(value);
        return StringUtils.hasText(text) ? text : defaultValue;
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }
}
