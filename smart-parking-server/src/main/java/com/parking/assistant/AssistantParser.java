package com.parking.assistant;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AssistantParser {

    private static final Pattern LAST_DAYS_PATTERN = Pattern.compile("最近\\s*(\\d{1,3})\\s*(天|日)");
    private static final Pattern DATE_RANGE_PATTERN = Pattern.compile("(\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2})\\s*(?:到|至|~)\\s*(\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2})");
    private static final Pattern SPOT_PATTERN = Pattern.compile("([A-Z]-\\d{2})");
    private static final Pattern PHONE_PATTERN = Pattern.compile("(1\\d{10})");
    private static final Pattern RECORD_ID_PATTERN = Pattern.compile("(?:停车)?记录\\s*(\\d+)");
    private static final Pattern FEE_PATTERN = Pattern.compile("(?:费用|金额)\\s*(?:改成|修改为|设为)?\\s*(\\d+(?:\\.\\d{1,2})?)");
    private static final Pattern MIN_ACCURACY_PATTERN = Pattern.compile("(?:准确率|置信度)\\s*(?:大于等于|不低于|至少|大于|高于|>=|>)\\s*(\\d{1,3}(?:\\.\\d{1,2})?)");
    private static final Pattern PASSWORD_CHANGE_PATTERN = Pattern.compile("密码.*从\\s*(\\S+)\\s*(?:改成|修改为|设为)\\s*(\\S+)");
    private static final Pattern PASSWORD_PAIR_PATTERN = Pattern.compile("旧密码\\s*(\\S+)\\s*新密码\\s*(\\S+)");
    private static final Pattern USER_PHONE_UPDATE_PATTERN = Pattern.compile("把?用户?(.+?)(?:的)?(?:手机号|电话)\\s*(?:改成|修改为|设为)\\s*(1\\d{10})");
    private static final Pattern USER_NAME_UPDATE_PATTERN = Pattern.compile("把?用户?(.+?)(?:的)?(?:姓名|名字|真实姓名)\\s*(?:改成|修改为|设为)\\s*(.+)$");
    private static final Pattern USER_USERNAME_UPDATE_PATTERN = Pattern.compile("把?用户?(.+?)(?:的)?(?:用户名|账号)\\s*(?:改成|修改为|设为)\\s*(.+)$");
    private static final Pattern ROLE_ASSIGN_PATTERN = Pattern.compile("给\\s*(.+?)\\s*(?:分配|设置为)\\s*(.+?)(?:角色)?$");
    private static final Pattern USER_ROLE_UPDATE_PATTERN = Pattern.compile("把?用户?(.+?)(?:的)?角色\\s*(?:改成|修改为|设为|设置为)\\s*(.+)$");
    private static final Pattern ROLE_PERMISSION_UPDATE_PATTERN = Pattern.compile("(?:把|将)?\\s*(.+?)\\s*(?:角色)?(?:的)?权限\\s*(?:改成|改为|设为|设置为|调整为|更新为)\\s*(.+)$");
    private static final Pattern PROFILE_NAME_PATTERN = Pattern.compile("(?:姓名|名字|真实姓名)\\s*(?:改成|修改为|设为)\\s*(.+)$");
    private static final Pattern PROFILE_USERNAME_PATTERN = Pattern.compile("(?:用户名|账号)\\s*(?:改成|修改为|设为)\\s*(.+)$");
    private static final Pattern PLATE_FULL_PATTERN = Pattern.compile("([京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼][A-Z][A-Z0-9]{4,5})");
    private static final Pattern PLATE_PREFIX_PATTERN = Pattern.compile("([京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼][A-Z])");

    public AssistantIntent parse(String rawMessage, Set<AssistantCapability> availableCapabilities) {
        if (!StringUtils.hasText(rawMessage)) {
            return null;
        }

        String message = rawMessage.trim();

        AssistantIntent intent = parseDashboard(message, availableCapabilities);
        if (intent != null) {
            return intent;
        }

        intent = parseParking(message, availableCapabilities);
        if (intent != null) {
            return intent;
        }

        intent = parseRecognition(message, availableCapabilities);
        if (intent != null) {
            return intent;
        }

        intent = parseDataCenter(message, availableCapabilities);
        if (intent != null) {
            return intent;
        }

        intent = parseAdmin(message, availableCapabilities);
        if (intent != null) {
            return intent;
        }

        return parseProfile(message, availableCapabilities);
    }

    private AssistantIntent parseDashboard(String message, Set<AssistantCapability> availableCapabilities) {
        if (!availableCapabilities.contains(AssistantCapability.DASHBOARD_REALTIME)) {
            return null;
        }
        if (!(message.contains("首页") || message.contains("仪表盘") || message.contains("概览") || message.contains("实时"))) {
            return null;
        }

        Map<String, Object> params = new HashMap<>();
        params.put("range", resolveDashboardRange(message));
        return new AssistantIntent(AssistantCapability.DASHBOARD_REALTIME, params);
    }

    private AssistantIntent parseParking(String message, Set<AssistantCapability> availableCapabilities) {
        AssistantIntent detailIntent = parseParkingRecordDetail(message, availableCapabilities);
        if (detailIntent != null) {
            return detailIntent;
        }

        AssistantIntent updateIntent = parseParkingRecordUpdate(message, availableCapabilities);
        if (updateIntent != null) {
            return updateIntent;
        }

        if (availableCapabilities.contains(AssistantCapability.PARKING_SPOT_ASSIGN)
                && (message.contains("分配车位") || message.contains("分配到"))) {
            Map<String, Object> params = new HashMap<>();
            putIfHasText(params, "plateNumber", extractFullPlate(message));
            putIfHasText(params, "targetSpotNo", extractSpotNo(message));
            return new AssistantIntent(AssistantCapability.PARKING_SPOT_ASSIGN, params);
        }

        if (availableCapabilities.contains(AssistantCapability.PARKING_SPOT_STATUS_UPDATE)
                && message.contains("车位")
                && (message.contains("改成") || message.contains("修改为") || message.contains("设为"))) {
            Map<String, Object> params = new HashMap<>();
            putIfHasText(params, "spotNo", extractSpotNo(message));
            putIfHasText(params, "targetStatus", resolveSpotStatus(message));
            putIfHasText(params, "plateNumber", extractFullPlate(message));
            return new AssistantIntent(AssistantCapability.PARKING_SPOT_STATUS_UPDATE, params);
        }

        if (availableCapabilities.contains(AssistantCapability.PARKING_ASSIGNMENT_VEHICLES)
                && (message.contains("待分配") || message.contains("可分配车辆"))) {
            return new AssistantIntent(AssistantCapability.PARKING_ASSIGNMENT_VEHICLES, Map.of());
        }

        if (availableCapabilities.contains(AssistantCapability.PARKING_SPOT_LIST)
                && message.contains("车位")
                && (message.contains("状态") || message.contains("列表") || message.contains("空闲") || message.contains("占用") || message.contains("维护") || message.contains("预约"))) {
            Map<String, Object> params = new HashMap<>();
            putIfHasText(params, "status", resolveSpotStatus(message));
            return new AssistantIntent(AssistantCapability.PARKING_SPOT_LIST, params);
        }

        if (!availableCapabilities.contains(AssistantCapability.PARKING_RECORD_QUERY)) {
            return null;
        }
        if (!(message.contains("进出记录") || message.contains("停车记录"))) {
            return null;
        }

        Map<String, Object> params = new HashMap<>();
        fillCommonRecordFilters(message, params);
        putIfHasText(params, "status", resolveRecordStatus(message));
        return new AssistantIntent(AssistantCapability.PARKING_RECORD_QUERY, params);
    }

    private AssistantIntent parseParkingRecordDetail(String message, Set<AssistantCapability> availableCapabilities) {
        if (!availableCapabilities.contains(AssistantCapability.PARKING_RECORD_DETAIL)) {
            return null;
        }
        if (!(message.contains("记录") && (message.contains("详情") || message.contains("详细")))) {
            return null;
        }

        Long recordId = extractRecordId(message);
        if (recordId == null) {
            return null;
        }
        return new AssistantIntent(AssistantCapability.PARKING_RECORD_DETAIL, Map.of("recordId", recordId));
    }

    private AssistantIntent parseParkingRecordUpdate(String message, Set<AssistantCapability> availableCapabilities) {
        if (!availableCapabilities.contains(AssistantCapability.PARKING_RECORD_UPDATE)) {
            return null;
        }
        if (!message.contains("记录")) {
            return null;
        }

        boolean updateRequested = message.contains("改成")
                || message.contains("修改为")
                || message.contains("设为")
                || message.contains("结算")
                || message.contains("出场");
        if (!updateRequested) {
            return null;
        }

        Long recordId = extractRecordId(message);
        if (recordId == null) {
            return null;
        }

        Map<String, Object> params = new HashMap<>();
        params.put("recordId", recordId);
        putIfHasText(params, "plateNumber", extractFullPlate(message));
        putIfHasText(params, "parkNo", extractSpotNo(message));
        putIfHasText(params, "status", resolveRecordStatus(message));
        putIfHasText(params, "fee", extractFee(message));
        return new AssistantIntent(AssistantCapability.PARKING_RECORD_UPDATE, params);
    }

    private AssistantIntent parseRecognition(String message, Set<AssistantCapability> availableCapabilities) {
        AssistantIntent exportIntent = parseRecognitionExport(message, availableCapabilities);
        if (exportIntent != null) {
            return exportIntent;
        }

        if (availableCapabilities.contains(AssistantCapability.RECOGNITION_VIDEO_ACCESS_GUIDE)
                && (message.contains("视频接入") || message.contains("摄像头接入") || message.contains("流接入"))) {
            return new AssistantIntent(AssistantCapability.RECOGNITION_VIDEO_ACCESS_GUIDE, Map.of());
        }

        if (!availableCapabilities.contains(AssistantCapability.RECOGNITION_RECORD_QUERY)) {
            return null;
        }
        if (!(message.contains("识别记录") || message.contains("识别日志"))) {
            return null;
        }

        Map<String, Object> params = new HashMap<>();
        fillCommonRecordFilters(message, params);
        putIfHasText(params, "minAccuracy", extractMinAccuracy(message));
        if (message.contains("图片")) {
            params.put("recognitionType", "IMAGE");
        } else if (message.contains("视频")) {
            params.put("recognitionType", "VIDEO");
        }
        return new AssistantIntent(AssistantCapability.RECOGNITION_RECORD_QUERY, params);
    }

    private AssistantIntent parseRecognitionExport(String message, Set<AssistantCapability> availableCapabilities) {
        if (!availableCapabilities.contains(AssistantCapability.RECOGNITION_RECORD_EXPORT_EXCEL)) {
            return null;
        }
        if (!(message.contains("识别记录") && (message.contains("导出") || message.contains("下载")))) {
            return null;
        }

        Map<String, Object> params = new HashMap<>();
        fillCommonRecordFilters(message, params);
        putIfHasText(params, "minAccuracy", extractMinAccuracy(message));
        if (message.contains("图片")) {
            params.put("recognitionType", "IMAGE");
        } else if (message.contains("视频")) {
            params.put("recognitionType", "VIDEO");
        }
        return new AssistantIntent(AssistantCapability.RECOGNITION_RECORD_EXPORT_EXCEL, params);
    }

    private AssistantIntent parseDataCenter(String message, Set<AssistantCapability> availableCapabilities) {
        boolean mentionsDataCenter = message.contains("数据中心") || message.contains("统计");
        if (!mentionsDataCenter) {
            return null;
        }

        AssistantIntent exportIntent = parseDataCenterExport(message, availableCapabilities);
        if (exportIntent != null) {
            return exportIntent;
        }

        if (availableCapabilities.contains(AssistantCapability.DATACENTER_RECORD_QUERY)
                && message.contains("记录")) {
            Map<String, Object> params = new HashMap<>();
            fillCommonRecordFilters(message, params);
            params.put("rangePreset", resolveDataCenterRangePreset(message));
            putIfHasText(params, "status", resolveRecordStatus(message));
            return new AssistantIntent(AssistantCapability.DATACENTER_RECORD_QUERY, params);
        }

        if (!availableCapabilities.contains(AssistantCapability.DATACENTER_OVERVIEW)) {
            return null;
        }

        Map<String, Object> params = new HashMap<>();
        fillCommonRecordFilters(message, params);
        params.put("rangePreset", resolveDataCenterRangePreset(message));
        putIfHasText(params, "status", resolveRecordStatus(message));
        return new AssistantIntent(AssistantCapability.DATACENTER_OVERVIEW, params);
    }

    private AssistantIntent parseDataCenterExport(String message, Set<AssistantCapability> availableCapabilities) {
        if (!(message.contains("导出") || message.contains("下载"))) {
            return null;
        }

        AssistantCapability capability;
        if (containsPdf(message)) {
            if (!availableCapabilities.contains(AssistantCapability.DATACENTER_RECORD_EXPORT_PDF)) {
                return null;
            }
            capability = AssistantCapability.DATACENTER_RECORD_EXPORT_PDF;
        } else if (containsExcel(message)) {
            if (!availableCapabilities.contains(AssistantCapability.DATACENTER_RECORD_EXPORT_EXCEL)) {
                return null;
            }
            capability = AssistantCapability.DATACENTER_RECORD_EXPORT_EXCEL;
        } else if (availableCapabilities.contains(AssistantCapability.DATACENTER_RECORD_EXPORT_EXCEL)) {
            capability = AssistantCapability.DATACENTER_RECORD_EXPORT_EXCEL;
        } else if (availableCapabilities.contains(AssistantCapability.DATACENTER_RECORD_EXPORT_PDF)) {
            capability = AssistantCapability.DATACENTER_RECORD_EXPORT_PDF;
        } else {
            return null;
        }

        Map<String, Object> params = new HashMap<>();
        fillCommonRecordFilters(message, params);
        params.put("rangePreset", resolveDataCenterRangePreset(message));
        putIfHasText(params, "status", resolveRecordStatus(message));
        return new AssistantIntent(capability, params);
    }

    private AssistantIntent parseAdmin(String message, Set<AssistantCapability> availableCapabilities) {
        AssistantIntent updateIntent = parseAdminUserUpdate(message, availableCapabilities);
        if (updateIntent != null) {
            return updateIntent;
        }

        AssistantIntent rolePermissionIntent = parseRolePermissionUpdate(message, availableCapabilities);
        if (rolePermissionIntent != null) {
            return rolePermissionIntent;
        }

        if (availableCapabilities.contains(AssistantCapability.ADMIN_ASSIGN_ROLE)) {
            Matcher roleUpdateMatcher = USER_ROLE_UPDATE_PATTERN.matcher(message);
            if (roleUpdateMatcher.find()) {
                Map<String, Object> params = new HashMap<>();
                params.put("userHint", roleUpdateMatcher.group(1).trim());
                params.put("roleHint", roleUpdateMatcher.group(2).trim());
                return new AssistantIntent(AssistantCapability.ADMIN_ASSIGN_ROLE, params);
            }

            Matcher matcher = ROLE_ASSIGN_PATTERN.matcher(message);
            if (matcher.find()) {
                Map<String, Object> params = new HashMap<>();
                params.put("userHint", matcher.group(1).trim());
                params.put("roleHint", matcher.group(2).trim());
                return new AssistantIntent(AssistantCapability.ADMIN_ASSIGN_ROLE, params);
            }
        }

        if (availableCapabilities.contains(AssistantCapability.ADMIN_PERMISSION_TREE)
                && (message.contains("权限树") || message.contains("权限列表") || message.contains("有哪些权限"))) {
            return new AssistantIntent(AssistantCapability.ADMIN_PERMISSION_TREE, Map.of());
        }

        if (availableCapabilities.contains(AssistantCapability.ADMIN_ROLE_LIST)
                && message.contains("角色")
                && !message.contains("分配")
                && !message.contains("设置")) {
            return new AssistantIntent(AssistantCapability.ADMIN_ROLE_LIST, Map.of());
        }

        if (availableCapabilities.contains(AssistantCapability.ADMIN_OPERATION_LOGS)
                && message.contains("操作日志")) {
            return new AssistantIntent(AssistantCapability.ADMIN_OPERATION_LOGS, Map.of("keyword", extractKeywordAfter(message, "操作日志")));
        }

        if (availableCapabilities.contains(AssistantCapability.ADMIN_LOGIN_LOGS)
                && message.contains("登录日志")
                && !message.contains("我的")
                && !message.contains("个人")) {
            return new AssistantIntent(AssistantCapability.ADMIN_LOGIN_LOGS, Map.of("keyword", extractKeywordAfter(message, "登录日志")));
        }

        if (!availableCapabilities.contains(AssistantCapability.ADMIN_USER_PAGE)) {
            return null;
        }
        if (!(message.contains("用户") && (message.contains("查询") || message.contains("搜索") || message.contains("列表") || message.contains("查")))) {
            return null;
        }

        return new AssistantIntent(AssistantCapability.ADMIN_USER_PAGE, Map.of("keyword", extractKeywordAfter(message, "用户")));
    }

    private AssistantIntent parseAdminUserUpdate(String message, Set<AssistantCapability> availableCapabilities) {
        if (!availableCapabilities.contains(AssistantCapability.ADMIN_USER_UPDATE)) {
            return null;
        }

        Map<String, Object> params = new HashMap<>();
        Matcher phoneMatcher = USER_PHONE_UPDATE_PATTERN.matcher(message);
        if (phoneMatcher.find()) {
            params.put("userHint", phoneMatcher.group(1).trim());
            params.put("phone", phoneMatcher.group(2).trim());
            return new AssistantIntent(AssistantCapability.ADMIN_USER_UPDATE, params);
        }

        Matcher nameMatcher = USER_NAME_UPDATE_PATTERN.matcher(message);
        if (nameMatcher.find()) {
            params.put("userHint", nameMatcher.group(1).trim());
            params.put("realName", nameMatcher.group(2).trim());
            return new AssistantIntent(AssistantCapability.ADMIN_USER_UPDATE, params);
        }

        Matcher usernameMatcher = USER_USERNAME_UPDATE_PATTERN.matcher(message);
        if (usernameMatcher.find()) {
            params.put("userHint", usernameMatcher.group(1).trim());
            params.put("username", usernameMatcher.group(2).trim());
            return new AssistantIntent(AssistantCapability.ADMIN_USER_UPDATE, params);
        }

        if (message.contains("禁用用户")) {
            params.put("userHint", message.substring(message.indexOf("禁用用户") + 4).trim());
            params.put("status", "DISABLED");
            return new AssistantIntent(AssistantCapability.ADMIN_USER_UPDATE, params);
        }

        if (message.contains("启用用户")) {
            params.put("userHint", message.substring(message.indexOf("启用用户") + 4).trim());
            params.put("status", "ENABLED");
            return new AssistantIntent(AssistantCapability.ADMIN_USER_UPDATE, params);
        }

        return null;
    }

    private AssistantIntent parseRolePermissionUpdate(String message, Set<AssistantCapability> availableCapabilities) {
        if (!availableCapabilities.contains(AssistantCapability.ADMIN_ROLE_PERMISSION_UPDATE)) {
            return null;
        }

        Matcher matcher = ROLE_PERMISSION_UPDATE_PATTERN.matcher(message);
        if (!matcher.find()) {
            return null;
        }

        Map<String, Object> params = new HashMap<>();
        params.put("roleHint", matcher.group(1).trim());
        params.put("permissionsText", matcher.group(2).trim());
        return new AssistantIntent(AssistantCapability.ADMIN_ROLE_PERMISSION_UPDATE, params);
    }

    private AssistantIntent parseProfile(String message, Set<AssistantCapability> availableCapabilities) {
        AssistantIntent passwordIntent = parseProfilePassword(message, availableCapabilities);
        if (passwordIntent != null) {
            return passwordIntent;
        }

        if (availableCapabilities.contains(AssistantCapability.PROFILE_UPDATE)
                && (message.contains("我的手机号") || message.contains("我的电话") || message.contains("我的姓名") || message.contains("我的名字") || message.contains("我的用户名") || message.contains("我的账号"))) {
            Map<String, Object> params = new HashMap<>();
            putIfHasText(params, "phone", extractPhone(message));

            Matcher nameMatcher = PROFILE_NAME_PATTERN.matcher(message);
            if (nameMatcher.find()) {
                params.put("realName", nameMatcher.group(1).trim());
            }

            Matcher usernameMatcher = PROFILE_USERNAME_PATTERN.matcher(message);
            if (usernameMatcher.find()) {
                params.put("username", usernameMatcher.group(1).trim());
            }
            return new AssistantIntent(AssistantCapability.PROFILE_UPDATE, params);
        }

        if (availableCapabilities.contains(AssistantCapability.PROFILE_LOGIN_LOGS)
                && (message.contains("我的登录日志") || message.contains("个人登录日志"))) {
            return new AssistantIntent(AssistantCapability.PROFILE_LOGIN_LOGS, Map.of());
        }

        if (!availableCapabilities.contains(AssistantCapability.PROFILE_ME)) {
            return null;
        }
        if (!(message.contains("个人信息") || message.contains("个人资料") || message.contains("我的资料") || message.contains("我的信息"))) {
            return null;
        }

        return new AssistantIntent(AssistantCapability.PROFILE_ME, Map.of());
    }

    private AssistantIntent parseProfilePassword(String message, Set<AssistantCapability> availableCapabilities) {
        if (!availableCapabilities.contains(AssistantCapability.PROFILE_PASSWORD_CHANGE)) {
            return null;
        }
        if (!message.contains("密码")) {
            return null;
        }

        Matcher matcher = PASSWORD_CHANGE_PATTERN.matcher(message);
        if (matcher.find()) {
            return new AssistantIntent(AssistantCapability.PROFILE_PASSWORD_CHANGE, Map.of(
                    "oldPassword", matcher.group(1).trim(),
                    "newPassword", matcher.group(2).trim()
            ));
        }

        Matcher pairMatcher = PASSWORD_PAIR_PATTERN.matcher(message);
        if (pairMatcher.find()) {
            return new AssistantIntent(AssistantCapability.PROFILE_PASSWORD_CHANGE, Map.of(
                    "oldPassword", pairMatcher.group(1).trim(),
                    "newPassword", pairMatcher.group(2).trim()
            ));
        }

        return null;
    }

    private void fillCommonRecordFilters(String message, Map<String, Object> params) {
        String fullPlate = extractFullPlate(message);
        if (StringUtils.hasText(fullPlate)) {
            params.put("plateNumber", fullPlate);
        } else {
            String platePrefix = extractPlatePrefix(message);
            if (StringUtils.hasText(platePrefix)) {
                params.put("plateNumber", platePrefix);
            }
        }

        String spotNo = extractSpotNo(message);
        if (StringUtils.hasText(spotNo)) {
            params.put("parkNo", spotNo);
        }

        if (fillExplicitDateRange(message, params)) {
            return;
        }

        int lastDays = extractLastDays(message);
        if (lastDays > 0) {
            LocalDate startDate = LocalDate.now().minusDays(lastDays - 1L);
            params.put("startTime", LocalDateTime.of(startDate, LocalTime.MIN));
            params.put("endTime", LocalDateTime.of(LocalDate.now(), LocalTime.MAX.withNano(0)));
            return;
        }

        if (message.contains("今天")) {
            LocalDate today = LocalDate.now();
            params.put("startTime", LocalDateTime.of(today, LocalTime.MIN));
            params.put("endTime", LocalDateTime.of(today, LocalTime.MAX.withNano(0)));
            return;
        }

        if (message.contains("本周")) {
            LocalDate today = LocalDate.now();
            LocalDate weekStart = today.minusDays(today.getDayOfWeek().getValue() - 1L);
            params.put("startTime", LocalDateTime.of(weekStart, LocalTime.MIN));
            params.put("endTime", LocalDateTime.of(today, LocalTime.MAX.withNano(0)));
            return;
        }

        if (message.contains("本月")) {
            LocalDate today = LocalDate.now();
            LocalDate monthStart = today.withDayOfMonth(1);
            params.put("startTime", LocalDateTime.of(monthStart, LocalTime.MIN));
            params.put("endTime", LocalDateTime.of(today, LocalTime.MAX.withNano(0)));
        }
    }

    private boolean fillExplicitDateRange(String message, Map<String, Object> params) {
        Matcher matcher = DATE_RANGE_PATTERN.matcher(message);
        if (!matcher.find()) {
            return false;
        }

        LocalDate startDate = parseDate(matcher.group(1));
        LocalDate endDate = parseDate(matcher.group(2));
        params.put("startTime", LocalDateTime.of(startDate, LocalTime.MIN));
        params.put("endTime", LocalDateTime.of(endDate, LocalTime.MAX.withNano(0)));
        return true;
    }

    private String resolveDashboardRange(String message) {
        if (message.contains("本周")) {
            return "THIS_WEEK";
        }
        if (message.contains("本月")) {
            return "THIS_MONTH";
        }
        return "TODAY";
    }

    private String resolveDataCenterRangePreset(String message) {
        if (message.contains("今天")) {
            return "TODAY";
        }
        if (message.contains("本周")) {
            return "THIS_WEEK";
        }
        if (message.contains("本月")) {
            return "THIS_MONTH";
        }
        return "LAST_30_DAYS";
    }

    private String resolveSpotStatus(String message) {
        if (message.contains("空闲")) {
            return "FREE";
        }
        if (message.contains("占用")) {
            return "OCCUPIED";
        }
        if (message.contains("维护")) {
            return "MAINTENANCE";
        }
        if (message.contains("预约")) {
            return "RESERVED";
        }
        return null;
    }

    private String resolveRecordStatus(String message) {
        if (message.contains("未出场")) {
            return "未出场";
        }
        if (message.contains("已出场") || message.contains("结算") || message.contains("出场")) {
            return "已出场";
        }
        return null;
    }

    private int extractLastDays(String message) {
        Matcher matcher = LAST_DAYS_PATTERN.matcher(message);
        if (!matcher.find()) {
            return 0;
        }
        return Integer.parseInt(matcher.group(1));
    }

    private Long extractRecordId(String message) {
        Matcher matcher = RECORD_ID_PATTERN.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        return Long.parseLong(matcher.group(1));
    }

    private String extractSpotNo(String message) {
        Matcher matcher = SPOT_PATTERN.matcher(message.toUpperCase());
        return matcher.find() ? matcher.group(1) : null;
    }

    private String extractPhone(String message) {
        Matcher matcher = PHONE_PATTERN.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String extractFee(String message) {
        Matcher matcher = FEE_PATTERN.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String extractMinAccuracy(String message) {
        Matcher matcher = MIN_ACCURACY_PATTERN.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String extractFullPlate(String message) {
        Matcher matcher = PLATE_FULL_PATTERN.matcher(message.toUpperCase());
        return matcher.find() ? matcher.group(1) : null;
    }

    private String extractPlatePrefix(String message) {
        Matcher matcher = PLATE_PREFIX_PATTERN.matcher(message.toUpperCase());
        return matcher.find() ? matcher.group(1) : null;
    }

    private boolean containsExcel(String message) {
        return message.contains("excel")
                || message.contains("Excel")
                || message.contains("xlsx")
                || message.contains("表格")
                || message.contains("电子表格");
    }

    private boolean containsPdf(String message) {
        return message.contains("pdf")
                || message.contains("PDF")
                || message.contains("文档")
                || message.contains("电子文档");
    }

    private LocalDate parseDate(String value) {
        String normalized = value.replace('/', '-').replace('.', '-');
        return LocalDate.parse(normalized, DateTimeFormatter.ofPattern("yyyy-M-d"));
    }

    private String extractKeywordAfter(String message, String marker) {
        int index = message.indexOf(marker);
        if (index < 0) {
            return null;
        }
        String suffix = message.substring(index + marker.length()).trim();
        if (!StringUtils.hasText(suffix)) {
            return null;
        }
        String normalized = suffix
                .replace("查询", "")
                .replace("搜索", "")
                .replace("列表", "")
                .replace("关键字", "")
                .trim();
        return StringUtils.hasText(normalized) ? normalized : null;
    }

    private void putIfHasText(Map<String, Object> params, String key, String value) {
        if (StringUtils.hasText(value)) {
            params.put(key, value);
        }
    }
}
